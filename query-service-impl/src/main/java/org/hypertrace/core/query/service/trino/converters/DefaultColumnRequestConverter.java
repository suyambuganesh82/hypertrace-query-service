package org.hypertrace.core.query.service.trino.converters;

import static org.hypertrace.core.query.service.QueryRequestUtil.getLogicalColumnName;
import static org.hypertrace.core.query.service.QueryRequestUtil.isAttributeExpressionWithSubpath;
import static org.hypertrace.core.query.service.QueryRequestUtil.isSimpleAttributeExpression;
import static org.hypertrace.core.query.service.api.Expression.ValueCase.LITERAL;
import static org.hypertrace.core.query.service.trino.converters.ColumnRequestContext.QueryPart.FILTER;
import static org.hypertrace.core.query.service.trino.converters.ColumnRequestContext.QueryPart.GROUP_BY;
import static org.hypertrace.core.query.service.trino.converters.ColumnRequestContext.QueryPart.ORDER_BY;
import static org.hypertrace.core.query.service.trino.converters.ColumnRequestContext.QueryPart.SELECT;
import static org.hypertrace.core.query.service.trino.converters.ColumnRequestContext.createColumnRequestContext;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.hypertrace.core.query.service.api.Expression;
import org.hypertrace.core.query.service.api.Filter;
import org.hypertrace.core.query.service.api.LiteralConstant;
import org.hypertrace.core.query.service.api.Operator;
import org.hypertrace.core.query.service.api.OrderByExpression;
import org.hypertrace.core.query.service.api.QueryRequest;
import org.hypertrace.core.query.service.api.Value;
import org.hypertrace.core.query.service.api.ValueType;
import org.hypertrace.core.query.service.trino.Params.Builder;
import org.hypertrace.core.query.service.trino.TableDefinition;
import org.jetbrains.annotations.NotNull;

/** Converts {@link QueryRequest} to Trino SQL query */
class DefaultColumnRequestConverter implements ColumnRequestConverter {

  private static final String QUESTION_MARK = "?";
  private static final String REGEX_OPERATOR = "~*";
  private static final int MAP_KEY_INDEX = 0;
  private static final int MAP_VALUE_INDEX = 1;

  private final TableDefinition tableDefinition;
  private final TrinoFunctionConverter functionConverter;

  DefaultColumnRequestConverter(
      TableDefinition tableDefinition, TrinoFunctionConverter functionConverter) {
    this.tableDefinition = tableDefinition;
    this.functionConverter = functionConverter;
  }

  @Override
  public String convertSelectClause(
      Expression expression, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    trinoExecutionContext.setColumnRequestContext(createColumnRequestContext(SELECT));
    String selectClause =
        convertExpressionToString(expression, paramsBuilder, trinoExecutionContext);
    trinoExecutionContext.resetColumnRequestContext();
    return selectClause;
  }

  @Override
  public String convertFilterClause(
      Filter filter, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    trinoExecutionContext.setColumnRequestContext(createColumnRequestContext(FILTER));
    String filterClause = convertFilterToString(filter, paramsBuilder, trinoExecutionContext);
    trinoExecutionContext.resetColumnRequestContext();
    return filterClause;
  }

  @Override
  public String convertGroupByClause(
      Expression expression, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    trinoExecutionContext.setColumnRequestContext(createColumnRequestContext(GROUP_BY));
    String groupByClause =
        convertExpressionToString(expression, paramsBuilder, trinoExecutionContext);
    trinoExecutionContext.resetColumnRequestContext();
    return groupByClause;
  }

  @Override
  public String convertOrderByClause(
      Expression expression, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    trinoExecutionContext.setColumnRequestContext(createColumnRequestContext(ORDER_BY));
    String orderByClause =
        convertExpressionToString(expression, paramsBuilder, trinoExecutionContext);
    trinoExecutionContext.resetColumnRequestContext();
    return orderByClause;
  }

  private String convertFilterToString(
      Filter filter, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    StringBuilder builder = new StringBuilder();
    String operator = convertOperatorToString(filter.getOperator());
    if (filter.getChildFilterCount() > 0) {
      String delim = "";
      builder.append("( ");
      for (Filter childFilter : filter.getChildFilterList()) {
        builder.append(delim);
        builder.append(convertFilterToString(childFilter, paramsBuilder, trinoExecutionContext));
        builder.append(" ");
        delim = operator + " ";
      }
      builder.append(")");
    } else {
      String lhs = convertExpressionToString(filter.getLhs(), paramsBuilder, trinoExecutionContext);
      switch (filter.getOperator()) {
        case LIKE:
          builder.append(lhs);
          builder.append(" ");
          builder.append(operator);
          builder.append(" ");
          builder.append(
              convertExpressionToString(filter.getRhs(), paramsBuilder, trinoExecutionContext));
          break;
        case CONTAINS_KEY:
          builder.append(lhs);
          builder.append("->>");
          builder.append(
              convertLiteralToString(
                  convertMapKeyExpressionToLiterals(filter.getRhs()), paramsBuilder));
          builder.append(" IS NOT NULL");
          break;
        case NOT_CONTAINS_KEY:
          builder.append(lhs);
          builder.append("->>");
          builder.append(
              convertLiteralToString(
                  convertMapKeyExpressionToLiterals(filter.getRhs()), paramsBuilder));
          builder.append(" IS NULL");
          break;
        case CONTAINS_KEYVALUE:
          List<LiteralConstant> kvp = convertMapKeyValueExpressionToLiterals(filter.getRhs());
          builder.append(lhs);
          builder.append("->>");
          builder.append(convertLiteralToString(kvp.get(MAP_KEY_INDEX), paramsBuilder));
          builder.append(" = ");
          builder.append(convertLiteralToString(kvp.get(MAP_VALUE_INDEX), paramsBuilder));
          break;
        case CONTAINS_KEY_LIKE:
          builder.append(lhs);
          builder.append("::jsonb::text");
          builder.append(" ");
          builder.append(operator);
          builder.append(" ");
          builder.append(
              convertLiteralToString(
                  convertMapLikeExpressionToLiterals(filter.getRhs()), paramsBuilder));
          break;
        default:
          if (isFilterForBytesColumnType(filter, trinoExecutionContext)) {
            handleConversionForBytesColumnExpression(
                lhs, operator, filter.getRhs(), builder, paramsBuilder);
          } else if (isFilterForArrayColumnType(filter, trinoExecutionContext)) {
            handleConversionForArrayColumnExpression(
                lhs, operator, filter.getRhs(), builder, paramsBuilder);
          } else {
            builder.append(lhs);
            builder.append(" ");
            builder.append(operator);
            builder.append(" ");
            builder.append(
                convertExpressionToString(filter.getRhs(), paramsBuilder, trinoExecutionContext));
          }
      }
    }
    return builder.toString();
  }

  private boolean isFilterForBytesColumnType(
      Filter filter, TrinoExecutionContext trinoExecutionContext) {
    return isSimpleAttributeExpression(filter.getLhs())
        && filter.getRhs().getValueCase().equals(LITERAL)
        && trinoExecutionContext.getColumnRequestContext().isBytesColumnType();
  }

  /** Handles value conversion of a bytes expression based */
  private void handleConversionForBytesColumnExpression(
      String lhs, String operator, Expression rhs, StringBuilder builder, Builder paramsBuilder) {
    Value value = rhs.getLiteral().getValue();

    if (handleConversionForNullOrEmptyBytesLiteral(lhs, operator, builder, value)) return;

    if (value.getValueType().equals(ValueType.STRING)) {
      isValidHexString(value.getString(), lhs);
    } else if (value.getValueType().equals(ValueType.STRING_ARRAY)) {
      value.getStringArrayList().forEach(strValue -> isValidHexString(strValue, lhs));
    } else {
      throw new IllegalArgumentException(
          String.format("Value not supported for bytes column : {%s}", value));
    }

    String convertedLiteral = convertLiteralToString(rhs.getLiteral(), paramsBuilder);

    builder.append(lhs);
    builder.append(" ");
    builder.append(operator);
    builder.append(" ");
    builder.append(convertedLiteral);
  }

  private boolean handleConversionForNullOrEmptyBytesLiteral(
      String lhs, String operator, StringBuilder builder, Value value) {
    if (value.getValueType().equals(ValueType.NULL_STRING)
        || (value.getValueType().equals(ValueType.STRING) && isNullOrEmpty(value.getString()))) {
      builder.append(lhs);
      builder.append(" ");
      if (!operator.equals("=") && !operator.equals("!=")) {
        throw new IllegalArgumentException(
            String.format("Unsupported operator {%s} for bytes column with empty value", operator));
      }
      builder.append(operator);
      builder.append(" ");
      builder.append("''");
      return true;
    }
    return false;
  }

  private boolean isFilterForArrayColumnType(
      Filter filter, TrinoExecutionContext trinoExecutionContext) {
    return isSimpleAttributeExpression(filter.getLhs())
        && filter.getRhs().getValueCase().equals(LITERAL)
        && trinoExecutionContext.getColumnRequestContext().isArrayColumnType();
  }

  /** Handles value conversion of a array expression based */
  private void handleConversionForArrayColumnExpression(
      String lhs, String operator, Expression rhs, StringBuilder builder, Builder paramsBuilder) {
    Value value = rhs.getLiteral().getValue();

    if (handleConversionForNullOrEmptyArrayLiteral(lhs, operator, builder, value)) return;

    // support only equals and IN operator
    // both of them are handled as contains check to align with existing implementation
    if (operator.equals("=")
        || operator.equals("IN")
        || operator.equals("!=")
        || operator.equals("NOT IN")) {
      // add NOT operator to negate the match condition
      if (operator.equals("!=") || operator.equals("NOT IN")) {
        builder.append("NOT ");
      }
      builder.append(lhs);
      // overlap operator for array
      builder.append(" && ");
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported operator {%s} for array column with non-empty value", operator));
    }
    builder.append("?");
    switch (value.getValueType()) {
      case STRING:
        paramsBuilder.addStringParam("{" + value.getString() + "}");
        break;
      case STRING_ARRAY:
        paramsBuilder.addStringParam("{" + String.join(", ", value.getStringArrayList()) + "}");
        break;
      default:
        throw new IllegalArgumentException(
            String.format("Unsupported value {%s} for array column", value));
    }
    builder.append(QUESTION_MARK);
  }

  private boolean handleConversionForNullOrEmptyArrayLiteral(
      String lhs, String operator, StringBuilder builder, Value value) {
    if (value.getValueType().equals(ValueType.NULL_STRING)
        || (value.getValueType().equals(ValueType.STRING) && isNullOrEmpty(value.getString()))) {
      builder.append(lhs);
      builder.append(" ");
      if (!operator.equals("=") && !operator.equals("!=")) {
        throw new IllegalArgumentException(
            String.format("Unsupported operator {%s} for bytes column with empty value", operator));
      }
      builder.append(operator);
      builder.append(" ");
      builder.append("'{}'");
      return true;
    }
    return false;
  }

  private boolean isNullOrEmpty(String strValue) {
    return Strings.isNullOrEmpty(strValue)
        || strValue.trim().equals("null")
        || strValue.trim().equals("''")
        || strValue.trim().equals("{}");
  }

  private void isValidHexString(String value, String columnName) {
    try {
      // decode string to hex to validate whether it is a valid hex string
      Hex.decodeHex(value);
    } catch (DecoderException e) {
      throw new IllegalArgumentException(
          String.format("Invalid input:{ %s } for bytes column:{ %s }", value, columnName));
    }
  }

  private String convertOperatorToString(Operator operator) {
    switch (operator) {
      case AND:
        return "AND";
      case OR:
        return "OR";
      case NOT:
        return "NOT";
      case EQ:
        return "=";
      case NEQ:
        return "!=";
      case IN:
        return "IN";
      case NOT_IN:
        return "NOT IN";
      case GT:
        return ">";
      case LT:
        return "<";
      case GE:
        return ">=";
      case LE:
        return "<=";
      case LIKE:
      case CONTAINS_KEY_LIKE:
        return REGEX_OPERATOR;
      case CONTAINS_KEY:
      case NOT_CONTAINS_KEY:
      case CONTAINS_KEYVALUE:
        return "";
      case RANGE:
        throw new UnsupportedOperationException("RANGE NOT supported use >= and <=");
      case UNRECOGNIZED:
      default:
        throw new UnsupportedOperationException("Unknown operator:" + operator);
    }
  }

  private String convertExpressionToString(
      Expression expression, Builder paramsBuilder, TrinoExecutionContext trinoExecutionContext) {
    switch (expression.getValueCase()) {
      case ATTRIBUTE_EXPRESSION:
        if (isAttributeExpressionWithSubpath(expression)) {
          String logicalColumnName =
              getLogicalColumnName(expression).orElseThrow(IllegalArgumentException::new);
          String columnName = tableDefinition.getPhysicalColumnName(logicalColumnName);
          String pathExpression = expression.getAttributeExpression().getSubpath();
          LiteralConstant pathExpressionLiteral =
              LiteralConstant.newBuilder()
                  .setValue(Value.newBuilder().setString(pathExpression).build())
                  .build();

          return columnName + "->>" + convertLiteralToString(pathExpressionLiteral, paramsBuilder);
        }
      case COLUMNIDENTIFIER:
        String logicalColumnName =
            getLogicalColumnName(expression).orElseThrow(IllegalArgumentException::new);
        String columnName = tableDefinition.getPhysicalColumnName(logicalColumnName);
        trinoExecutionContext.addActualTableColumnName(columnName);
        ColumnRequestContext context = trinoExecutionContext.getColumnRequestContext();
        context.setColumnValueType(tableDefinition.getColumnType(logicalColumnName));
        if (context.isBytesColumnType()) {
          return String.format("lower(to_hex(%s))", columnName);
        }
        return columnName;
      case LITERAL:
        return convertLiteralToString(expression.getLiteral(), paramsBuilder);
      case FUNCTION:
        return this.functionConverter.convert(
            trinoExecutionContext,
            expression.getFunction(),
            argExpression ->
                convertExpressionToString(argExpression, paramsBuilder, trinoExecutionContext));
      case ORDERBY:
        OrderByExpression orderBy = expression.getOrderBy();
        return convertExpressionToString(
            orderBy.getExpression(), paramsBuilder, trinoExecutionContext);
      case VALUE_NOT_SET:
        break;
    }
    return "";
  }

  private LiteralConstant convertMapKeyExpressionToLiterals(Expression expression) {
    List<String> literals = new ArrayList<>(1);
    if (expression.getValueCase() == LITERAL) {
      LiteralConstant value = expression.getLiteral();
      if (value.getValue().getValueType() == ValueType.STRING) {
        literals.add(value.getValue().getString());
      } else {
        throw new IllegalArgumentException("Unsupported arguments for CONTAINS_KEY operator");
      }
    }
    return getLiteralConstants(literals).get(0);
  }

  private List<LiteralConstant> convertMapKeyValueExpressionToLiterals(Expression expression) {
    List<String> literals = new ArrayList<>(2);
    if (expression.getValueCase() == LITERAL) {
      LiteralConstant value = expression.getLiteral();
      if (value.getValue().getValueType() == ValueType.STRING_ARRAY
          && value.getValue().getStringArrayCount() == 2) {
        literals.add(value.getValue().getStringArray(MAP_KEY_INDEX));
        literals.add(value.getValue().getStringArray(MAP_VALUE_INDEX));
      } else {
        throw new IllegalArgumentException("Unsupported arguments for CONTAINS_KEYVALUE  operator");
      }
    }
    return getLiteralConstants(literals);
  }

  private LiteralConstant convertMapLikeExpressionToLiterals(Expression expression) {
    List<String> literals = new ArrayList<>(1);
    if (expression.getValueCase() == LITERAL) {
      LiteralConstant value = expression.getLiteral();
      if (value.getValue().getValueType() == ValueType.STRING) {
        literals.add(".*\"" + value.getValue().getString() + "\":.*");
      } else {
        throw new IllegalArgumentException("Unsupported arguments for CONTAINS_KEY_LIKE operator");
      }
    }
    return getLiteralConstants(literals).get(0);
  }

  @NotNull
  private List<LiteralConstant> getLiteralConstants(List<String> literals) {
    return literals.stream()
        .map(
            literal ->
                LiteralConstant.newBuilder()
                    .setValue(Value.newBuilder().setString(literal))
                    .build())
        .collect(Collectors.toUnmodifiableList());
  }

  /** TODO:Handle all types */
  private String convertLiteralToString(LiteralConstant literal, Builder paramsBuilder) {
    Value value = literal.getValue();
    String ret = null;
    switch (value.getValueType()) {
      case STRING_ARRAY:
        StringBuilder builder = new StringBuilder("(");
        String delim = "";
        for (String item : value.getStringArrayList()) {
          builder.append(delim);
          builder.append(QUESTION_MARK);
          paramsBuilder.addStringParam(item);
          delim = ", ";
        }
        builder.append(")");
        ret = builder.toString();
        break;
      case BYTES_ARRAY:
        builder = new StringBuilder("(");
        delim = "";
        for (ByteString item : value.getBytesArrayList()) {
          builder.append(delim);
          builder.append(QUESTION_MARK);
          paramsBuilder.addByteStringParam(item);
          delim = ", ";
        }
        builder.append(")");
        ret = builder.toString();
        break;
      case STRING:
        ret = QUESTION_MARK;
        paramsBuilder.addStringParam(value.getString());
        break;
      case LONG:
        ret = QUESTION_MARK;
        paramsBuilder.addLongParam(value.getLong());
        break;
      case INT:
        ret = QUESTION_MARK;
        paramsBuilder.addIntegerParam(value.getInt());
        break;
      case FLOAT:
        ret = QUESTION_MARK;
        paramsBuilder.addFloatParam(value.getFloat());
        break;
      case DOUBLE:
        ret = QUESTION_MARK;
        paramsBuilder.addDoubleParam(value.getDouble());
        break;
      case BYTES:
        ret = QUESTION_MARK;
        paramsBuilder.addByteStringParam(value.getBytes());
        break;
      case BOOL:
        ret = QUESTION_MARK;
        paramsBuilder.addBooleanParam(value.getBoolean());
        break;
      case TIMESTAMP:
        ret = QUESTION_MARK;
        paramsBuilder.addLongParam(value.getTimestamp());
        break;
      case NULL_NUMBER:
        ret = QUESTION_MARK;
        paramsBuilder.addIntegerParam(0);
        break;
      case NULL_STRING:
        ret = QUESTION_MARK;
        paramsBuilder.addStringParam("null");
        break;
      case LONG_ARRAY:
      case INT_ARRAY:
      case FLOAT_ARRAY:
      case DOUBLE_ARRAY:
      case BOOLEAN_ARRAY:
      case UNRECOGNIZED:
        break;
    }
    return ret;
  }
}