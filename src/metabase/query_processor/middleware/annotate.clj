(ns metabase.query-processor.middleware.annotate
  "Middleware for annotating (adding type information to) the results of a query, under the `:cols` column."
  (:require
   [clojure.string :as str]
   [medley.core :as m]
   [metabase.driver.common :as driver.common]
   [metabase.lib.metadata.calculate :as lib.metadata.calculate]
   [metabase.mbql.schema :as mbql.s]
   [metabase.mbql.util :as mbql.u]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.query-processor.middleware.escape-join-aliases
    :as escape-join-aliases]
   [metabase.query-processor.reducible :as qp.reducible]
   [metabase.query-processor.store :as qp.store]
   [metabase.query-processor.util :as qp.util]
   [metabase.sync.analyze.fingerprint.fingerprinters :as fingerprinters]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-tru tru]]
   [metabase.util.schema :as su]
   [schema.core :as s]))

(def ^:private Col
  "Schema for a valid map of column info as found in the `:cols` key of the results after this namespace has ran."
  ;; name and display name can be blank because some wacko DBMSes like SQL Server return blank column names for
  ;; unaliased aggregations like COUNT(*) (this only applies to native queries, since we determine our own names for
  ;; MBQL.)
  {:name                           s/Str
   :display_name                   s/Str
   ;; type of the Field. For Native queries we look at the values in the first 100 rows to make an educated guess
   :base_type                      su/FieldType
   ;; effective_type, coercion, etc don't go here. probably best to rename base_type to effective type in the return
   ;; from the metadata but that's for another day
   ;; where this column came from in the original query.
   :source                         (s/enum :aggregation :fields :breakout :native)
   ;; a field clause that can be used to refer to this Field if this query is subsequently used as a source query.
   ;; Added by this middleware as one of the last steps.
   (s/optional-key :field_ref)     mbql.s/FieldOrAggregationReference
   ;; various other stuff from the original Field can and should be included such as `:settings`
   s/Any                           s/Any})

;; TODO - I think we should change the signature of this to `(column-info query cols rows)`
(defmulti column-info
  "Determine the `:cols` info that should be returned in the query results, which is a sequence of maps containing
  information about the columns in the results. Dispatches on query type. `results` is a map with keys `:cols` and,
  optionally, `:rows`, if available."
  {:arglists '([query results])}
  (fn [query _]
    (:type query)))

(defmethod column-info :default
  [{query-type :type, :as query} _]
  (throw (ex-info (tru "Unknown query type {0}" (pr-str query-type))
           {:type  qp.error-type/invalid-query
            :query query})))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      Adding :cols info for native queries                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(s/defn ^:private check-driver-native-columns
  "Double-check that the *driver* returned the correct number of `columns` for native query results."
  [cols :- [{s/Any s/Any}], rows]
  (when (seq rows)
    (let [expected-count (count cols)
          actual-count   (count (first rows))]
      (when-not (= expected-count actual-count)
        (throw (ex-info (str (deferred-tru "Query processor error: number of columns returned by driver does not match results.")
                             "\n"
                             (deferred-tru "Expected {0} columns, but first row of resuls has {1} columns."
                               expected-count actual-count))
                 {:expected-columns (map :name cols)
                  :first-row        (first rows)
                  :type             qp.error-type/qp}))))))

(defn- annotate-native-cols [cols]
  (let [unique-name-fn (mbql.u/unique-name-generator)]
    (vec (for [{col-name :name, base-type :base_type, :as driver-col-metadata} cols]
           (let [col-name (name col-name)]
             (merge
              {:display_name (u/qualified-name col-name)
               :source       :native}
              ;; It is perfectly legal for a driver to return a column with a blank name; for example, SQL Server does
              ;; this for aggregations like `count(*)` if no alias is used. However, it is *not* legal to use blank
              ;; names in MBQL `:field` clauses, because `SELECT ""` doesn't make any sense. So if we can't return a
              ;; valid `:field`, omit the `:field_ref`.
              (when-not (str/blank? col-name)
                {:field_ref [:field (unique-name-fn col-name) {:base-type base-type}]})
              driver-col-metadata))))))

(defmethod column-info :native
  [_query {:keys [cols rows] :as _results}]
  (check-driver-native-columns cols rows)
  (annotate-native-cols cols))

(defn- check-correct-number-of-columns-returned [returned-mbql-columns results]
  (let [expected-count (count returned-mbql-columns)
        actual-count   (count (:cols results))]
    (when (seq (:rows results))
      (when-not (= expected-count actual-count)
        (throw
         (ex-info (str (tru "Query processor error: mismatched number of columns in query and results.")
                       " "
                       (tru "Expected {0} fields, got {1}" expected-count actual-count)
                       "\n"
                       (tru "Expected: {0}" (mapv :name returned-mbql-columns))
                       "\n"
                       (tru "Actual: {0}" (vec (:columns results))))
                  {:expected returned-mbql-columns
                   :actual   (:cols results)}))))))

(s/defn ^:private merge-source-metadata-col :- (s/maybe su/Map)
  [source-metadata-col :- (s/maybe su/Map) col :- (s/maybe su/Map)]
  (merge
    {} ;; ensure the type is not FieldInstance
    (when-let [field-id (:id source-metadata-col)]
      (dissoc (qp.store/field field-id) :database_type))
   source-metadata-col
   col
   ;; pass along the unit from the source query metadata if the top-level metadata has unit `:default`. This way the
   ;; frontend will display the results correctly if bucketing was applied in the nested query, e.g. it will format
   ;; temporal values in results using that unit
   (when (= (:unit col) :default)
     (select-keys source-metadata-col [:unit]))))

(defn- maybe-merge-source-metadata
  "Merge information from `source-metadata` into the returned `cols` for queries that return the columns of a source
  query as-is (i.e., the parent query does not have breakouts, aggregations, or an explicit`:fields` clause --
  excluding the one added automatically by `add-source-metadata`)."
  [source-metadata cols]
  (if (= (count cols) (count source-metadata))
    (map merge-source-metadata-col source-metadata cols)
    cols))

(defn- flow-field-metadata
  "Merge information about fields from `source-metadata` into the returned `cols`."
  [source-metadata cols dataset?]
  (let [by-key (m/index-by (comp qp.util/field-ref->key :field_ref) source-metadata)]
    (for [{:keys [field_ref source] :as col} cols]
     ;; aggregation fields are not from the source-metadata and their field_ref
     ;; are not unique for a nested query. So do not merge them otherwise the metadata will be messed up.
     ;; TODO: I think the best option here is to introduce a parent_field_ref so that
     ;; we could preserve metadata such as :sematic_type or :unit from the source field.
      (if-let [source-metadata-for-field (and (not= :aggregation source)
                                              (get by-key (qp.util/field-ref->key field_ref)))]
        (merge-source-metadata-col source-metadata-for-field
                                   (merge col
                                          (when dataset?
                                            (select-keys source-metadata-for-field qp.util/preserved-keys))))
        col))))

(declare mbql-cols)

(defn- cols-for-source-query
  [{:keys [source-metadata], {native-source-query :native, :as source-query} :source-query} results]
  (let [columns       (if native-source-query
                        (maybe-merge-source-metadata source-metadata (column-info {:type :native} results))
                        (mbql-cols source-query results))]
    (qp.util/combine-metadata columns source-metadata)))

(defn mbql-cols
  "Return the `:cols` result metadata for an 'inner' MBQL query based on the fields/breakouts/aggregations in the
  query."
  [{:keys [source-metadata source-query :source-query/dataset? fields], :as inner-query}, results]
  (let [cols (lib.metadata.calculate/cols-for-mbql-query inner-query)]
    (cond
      (and (empty? cols) source-query)
      (cols-for-source-query inner-query results)

      source-query
      (flow-field-metadata (cols-for-source-query inner-query results) cols dataset?)

      (every? #(mbql.u/match-one % [:field (field-name :guard string?) _] field-name) fields)
      (maybe-merge-source-metadata source-metadata cols)

      :else
      cols)))

(defmethod column-info :query
  [{inner-query :query} results]
  (u/prog1 (mbql-cols inner-query results)
    (check-correct-number-of-columns-returned <> results)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Deduplicating names                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ColsWithUniqueNames
  (s/constrained [Col] #(su/empty-or-distinct? (map :name %)) ":cols with unique names"))

(s/defn ^:private deduplicate-cols-names :- ColsWithUniqueNames
  [cols :- [Col]]
  (map (fn [col unique-name]
         (assoc col :name unique-name))
       cols
       (mbql.u/uniquify-names (map :name cols))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           add-column-info middleware                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- merge-col-metadata
  "Merge a map from `:cols` returned by the driver with the column metadata determined by the logic above."
  [our-col-metadata driver-col-metadata]
  ;; 1. Prefer our `:name` if it's something different that what's returned by the driver
  ;;    (e.g. for named aggregations)
  ;; 2. Prefer our inferred base type if the driver returned `:type/*` and ours is more specific
  ;; 3. Then, prefer any non-nil keys returned by the driver
  ;; 4. Finally, merge in any of our other keys
  (let [non-nil-driver-col-metadata (m/filter-vals some? driver-col-metadata)
        our-base-type               (when (= (:base_type driver-col-metadata) :type/*)
                                      (u/select-non-nil-keys our-col-metadata [:base_type]))
        ;; whatever type comes back from the query is by definition the effective type, fallback to our effective
        ;; type, fallback to the base_type
        effective-type              (when-let [db-base (or (:base_type driver-col-metadata)
                                                           (:effective_type our-col-metadata)
                                                           (:base_type our-col-metadata))]
                                      {:effective_type db-base})
        our-name                    (u/select-non-nil-keys our-col-metadata [:name])]
    (merge our-col-metadata
           non-nil-driver-col-metadata
           our-base-type
           our-name
           effective-type)))

(defn- merge-cols-returned-by-driver
  "Merge our column metadata (`:cols`) derived from logic above with the column metadata returned by the driver. We'll
  prefer the values in theirs to ours. This is important for wacky drivers like GA that use things like native
  metrics, which we have no information about.

  It's the responsibility of the driver to make sure the `:cols` are returned in the correct number and order."
  [our-cols cols-returned-by-driver]
  (if (seq cols-returned-by-driver)
    (mapv merge-col-metadata our-cols cols-returned-by-driver)
    our-cols))

(s/defn merged-column-info :- ColsWithUniqueNames
  "Returns deduplicated and merged column metadata (`:cols`) for query results by combining (a) the initial results
  metadata returned by the driver's impl of `execute-reducible-query` and (b) column metadata inferred by logic in
  this namespace."
  [query {cols-returned-by-driver :cols, :as result}]
  (deduplicate-cols-names
   (merge-cols-returned-by-driver (column-info query result) cols-returned-by-driver)))

(defn base-type-inferer
  "Native queries don't have the type information from the original `Field` objects used in the query.
  If the driver returned a base type more specific than :type/*, use that; otherwise look at the sample
  of rows and infer the base type based on the classes of the values"
  [{:keys [cols]}]
  (apply fingerprinters/col-wise
         (for [{driver-base-type :base_type} cols]
           (if (contains? #{nil :type/*} driver-base-type)
             (driver.common/values->base-type)
             (fingerprinters/constant-fingerprinter driver-base-type)))))

(defn- add-column-info-xform
  [query metadata rf]
  (qp.reducible/combine-additional-reducing-fns
   rf
   [(base-type-inferer metadata)
    ((take 1) conj)]
   (fn combine [result base-types truncated-rows]
     (let [metadata (update metadata :cols
                            (comp annotate-native-cols
                                  (fn [cols]
                                    (map (fn [col base-type]
                                           (-> col
                                               (assoc :base_type base-type)
                                               ;; annotate will add a field ref with type info
                                               (dissoc :field_ref)))
                                         cols
                                         base-types))))]
       (rf (cond-> result
             (map? result)
             (assoc-in [:data :cols]
                       (merged-column-info
                        query
                        (assoc metadata :rows truncated-rows)))))))))

(defn add-column-info
  "Middleware for adding type information about the columns in the query results (the `:cols` key)."
  [{query-type :type, :as query
    {:keys [:metadata/dataset-metadata :alias/escaped->original]} :info} rff]
  (fn add-column-info-rff* [metadata]
    (if (and (= query-type :query)
             ;; we should have type metadata eiter in the query fields
             ;; or in the result metadata for the following code to work
             (or (->> query :query keys (some #{:aggregation :breakout :fields}))
                 (every? :base_type (:cols metadata))))
      (let [query (cond-> query
                    (seq escaped->original) ;; if we replaced aliases, restore them
                    (escape-join-aliases/restore-aliases escaped->original))]
        (rff (cond-> (assoc metadata :cols (merged-column-info query metadata))
               (seq dataset-metadata)
               (update :cols qp.util/combine-metadata dataset-metadata))))
      ;; rows sampling is only needed for native queries! TODO ­ not sure we really even need to do for native
      ;; queries...
      (let [metadata (cond-> (update metadata :cols annotate-native-cols)
                       ;; annotate-native-cols ensures that column refs are present which we need to match metadata
                       (seq dataset-metadata)
                       (update :cols qp.util/combine-metadata dataset-metadata)
                       ;; but we want those column refs removed since they have type info which we don't know yet
                       :always
                       (update :cols (fn [cols] (map #(dissoc % :field_ref) cols))))]
        (add-column-info-xform query metadata (rff metadata))))))
