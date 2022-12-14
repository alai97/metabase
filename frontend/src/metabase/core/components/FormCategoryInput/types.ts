import Field from "metabase-lib/metadata/Field";

export interface CategoryWidgetProps {
  value: string;
  onChange: (value: string | null) => void;
  fieldInstance: Field;
}
