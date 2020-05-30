package io.mycat.mpp.runtime;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.math.BigDecimal;

@AllArgsConstructor
@EqualsAndHashCode
@Getter
public class Type {
    public static final int NULL = 0;
    public static final int INT = 1;
    public static final int VARCHAR = 2;
    public static final int DECIMAL = 3;
    //    public static final int BINARY = 4;
    public static final int TIMESTAMP = 5;
    public static final int DATETIME = 6;

    private final int base;
    private final boolean nullable;

    public static Type of(int base, boolean nullable) {
        return new Type(base, nullable);
    }

    public static Type of(String name, boolean nullable) {
        int base = 0;
        switch (name.toUpperCase()) {
            case "NULL": {
                base = NULL;
                break;
            }
            case "INT": {
                base = INT;
                break;
            }
            case "VARCHAR": {
                base = VARCHAR;
                break;
            }
            case "DECIMAL": {
                base = DECIMAL;
                break;
            }
            case "TIMESTAMP": {
                base = TIMESTAMP;
                break;
            }
            case "DATETIME": {
                base = DATETIME;
                break;
            }
            default:
                throw new IllegalArgumentException();
        }
        return of(base, nullable);
    }

    public Type toNullable() {
        if (!nullable) {
            return of(base, true);
        } else {
            return this;
        }
    }

    public boolean isInt() {
        return base == INT || base == DECIMAL;
    }

    public Type toDecimalType() {
        if (base != DECIMAL) {
            return of(DECIMAL, nullable);
        } else {
            return this;
        }
    }

    public boolean isString() {
        return base == VARCHAR;
    }

    public Type toIntType() {
        if (base != INT) {
            return of(INT, nullable);
        } else {
            return this;
        }
    }

    public Type toStringType() {
        if (base != VARCHAR) {
            return of(VARCHAR, nullable);
        } else {
            return this;
        }
    }

    public boolean isDecimal() {
        return base == DECIMAL;
    }

    public boolean isTimestampOrDateTime() {
        return base == TIMESTAMP || base == DATETIME;
    }

    public Type toTimestamp() {
        if (base != TIMESTAMP) {
            return of(TIMESTAMP, nullable);
        } else {
            return this;
        }
    }

    public Class<?> getJavaClass() {
        switch (base) {
            case INT:
                return Integer.class;
            case VARCHAR:
                return String.class;
            case DECIMAL:
                return BigDecimal.class;
            case TIMESTAMP:
                return java.sql.Timestamp.class;
            case DATETIME:
                return java.sql.Date.class;
            case NULL:
                return Void.TYPE;
            default:
                throw new UnsupportedOperationException();
        }
    }
    public static Type getFromClass(Class c) {
        int b = 0;
        if (c == Integer.class){
          b= INT;
        }
        if (c == Long.class){
            b= INT;
        }
        if (c == String.class){
            b= VARCHAR;
        }
        if (c == BigDecimal.class){
            b= DECIMAL;
        }
        if (c == java.sql.Timestamp.class){
            b= TIMESTAMP;
        }
        if (c == java.sql.Date.class){
            b= DATETIME;
        }
        if (c == Void.class){
            b= NULL;
        }
       return Type.of(b,false);
    }
    @Override
    public String toString() {
        String typeName = "";
        /*
           public static final int NULL = 0;
    public static final int INT = 1;
    public static final int VARCHAR = 2;
    public static final int DECIMAL = 3;
    //    public static final int BINARY = 4;
    public static final int TIMESTAMP = 5;
    public static final int DATETIME = 6;
         */
        typeName = getTypeName();
        return "Type{" +
                "base=" + typeName +
                ", nullable=" + nullable +
                '}';
    }

    public String getTypeName() {
        String typeName = null;
        switch (base){
            case NULL:
                typeName ="NULL";
                break;
            case INT:
                typeName ="INT";
                break;
            case VARCHAR:
                typeName ="VARCHAR";
                break;
            case DECIMAL:
                typeName ="DECIMAL";
                break;
            case TIMESTAMP:
                typeName ="TIMESTAMP";
                break;
            case DATETIME:
                typeName ="DATETIME";
                break;
        }
        return typeName;
    }
}