import { useEffect } from "react";
import type { Action } from "@reduxjs/toolkit";
import { useDispatch, useSelector } from "metabase/lib/redux";
import { State } from "metabase-types/store";

export interface EntityFetchOptions {
  reload?: boolean;
}

export interface EntityQueryOptions<TQuery> {
  entityQuery?: TQuery;
}

export interface UseEntityListOwnProps<TItem, TQuery> {
  fetchList: (query?: TQuery, options?: EntityFetchOptions) => Action;
  getList: (
    state: State,
    options: EntityQueryOptions<TQuery>,
  ) => TItem[] | undefined;
  getLoading: (state: State, options: EntityQueryOptions<TQuery>) => boolean;
  getError: (state: State, options: EntityQueryOptions<TQuery>) => unknown;
}

export interface UseEntityListQueryProps<TQuery> {
  query?: TQuery;
  reload?: boolean;
  enabled?: boolean;
}

export interface UseEntityListQueryResult<TItem> {
  data?: TItem[];
  isLoading: boolean;
  error: unknown;
}

export const useEntityListQuery = <TItem, TQuery>(
  {
    query: entityQuery,
    reload = false,
    enabled = true,
  }: UseEntityListQueryProps<TQuery>,
  {
    fetchList,
    getList,
    getLoading,
    getError,
  }: UseEntityListOwnProps<TItem, TQuery>,
): UseEntityListQueryResult<TItem> => {
  const options = { entityQuery };
  const data = useSelector(state => getList(state, options));
  const isLoading = useSelector(state => getLoading(state, options));
  const error = useSelector(state => getError(state, options));

  const dispatch = useDispatch();
  useEffect(() => {
    if (enabled) {
      dispatch(fetchList(entityQuery, { reload }));
    }
  }, [dispatch, fetchList, entityQuery, reload, enabled]);

  return { data, isLoading, error };
};
