import React, { useRef, useState } from "react";
import { t } from "ttag";
import { isNotNull } from "metabase/core/utils/types";
import Button from "metabase/core/components/Button";
import Input from "metabase/core/components/Input/Input";
import Tooltip from "metabase/core/components/Tooltip";
import MetabaseSettings from "metabase/lib/settings";
import type { Expression } from "metabase-types/types/Query";
import Icon from "metabase/components/Icon";
import { isExpression } from "metabase-lib/expressions";
import type StructuredQuery from "metabase-lib/queries/StructuredQuery";

import ExpressionEditorTextfield from "./ExpressionEditorTextfield";
import {
  ActionButtonsWrapper,
  Container,
  Divider,
  ExpressionFieldWrapper,
  FieldTitle,
  FieldWrapper,
  Footer,
  InfoLink,
  RemoveLink,
  StyledFieldTitleIcon,
} from "./ExpressionWidget.styled";

const EXPRESSIONS_DOCUMENTATION_URL = MetabaseSettings.docsUrl(
  "questions/query-builder/expressions",
);

export interface ExpressionWidgetProps {
  query: StructuredQuery;
  expression: Expression | undefined;
  name?: string;
  withName?: boolean;
  startRule?: string;

  title?: string;

  reportTimezone: string;

  onChangeExpression: (name: string, expression: Expression) => void;
  onRemoveExpression?: (name: string) => void;
  onClose?: () => void;
}

const ExpressionWidget = (props: ExpressionWidgetProps): JSX.Element => {
  const {
    query,
    name: initialName,
    expression: initialExpression,
    withName = false,
    startRule,
    title,
    reportTimezone,
    onChangeExpression,
    onRemoveExpression,
    onClose,
  } = props;

  const [name, setName] = useState(initialName || "");
  const [expression, setExpression] = useState<Expression | null>(
    initialExpression || null,
  );
  const [error, setError] = useState<string | null>(null);

  const helpTextTargetRef = useRef(null);

  const isValid =
    (withName ? !!name : true) && !error && isExpression(expression);

  const handleCommit = () => {
    if (isValid && isNotNull(expression)) {
      onChangeExpression(name, expression);
      onClose && onClose();
    }
  };

  return (
    <Container>
      {/* TODO: refactor to styled */}
      <div className="text-medium p1 py2 border-bottom flex align-center">
        <a className="cursor-pointer flex align-center" onClick={onClose}>
          <Icon name="chevronleft" size={18} />
          <h3 className="inline-block pl1">{title}</h3>
        </a>
      </div>
      <ExpressionFieldWrapper>
        <FieldTitle>
          {t`Expression`}

          <Tooltip
            tooltip={t`You can reference columns here in functions or equations, like: floor([Price] - [Discount]). Click for documentation.`}
            placement="right"
            maxWidth={332}
          >
            <InfoLink
              target="_blank"
              href={EXPRESSIONS_DOCUMENTATION_URL}
              aria-label={t`Open expressions documentation`}
            >
              <StyledFieldTitleIcon name="info" />
            </InfoLink>
          </Tooltip>
        </FieldTitle>
        <div ref={helpTextTargetRef}>
          <ExpressionEditorTextfield
            helpTextTarget={helpTextTargetRef.current}
            expression={expression}
            startRule={startRule}
            name={name}
            query={query}
            reportTimezone={reportTimezone}
            onChange={(parsedExpression: Expression) => {
              setExpression(parsedExpression);
              setError(null);
            }}
            onError={(errorMessage: string) => setError(errorMessage)}
          />
        </div>
      </ExpressionFieldWrapper>
      {withName && (
        <FieldWrapper>
          <FieldTitle>{t`Name`}</FieldTitle>
          <Input
            type="text"
            value={name}
            placeholder={t`Something nice and descriptive`}
            fullWidth
            onChange={event => setName(event.target.value)}
            onKeyPress={e => {
              if (e.key === "Enter") {
                handleCommit();
              }
            }}
          />
        </FieldWrapper>
      )}

      <Divider />
      <Footer>
        <ActionButtonsWrapper>
          {onClose && <Button onClick={onClose}>{t`Cancel`}</Button>}
          <Button primary={isValid} disabled={!isValid} onClick={handleCommit}>
            {initialName ? t`Update` : t`Done`}
          </Button>

          {initialName && onRemoveExpression ? (
            <RemoveLink
              onlyText
              onClick={() => {
                onRemoveExpression(initialName);
                onClose && onClose();
              }}
            >{t`Remove`}</RemoveLink>
          ) : null}
        </ActionButtonsWrapper>
      </Footer>
    </Container>
  );
};

export default ExpressionWidget;