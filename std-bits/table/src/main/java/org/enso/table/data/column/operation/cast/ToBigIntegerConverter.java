package org.enso.table.data.column.operation.cast;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.enso.table.data.column.builder.BigIntegerBuilder;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.numeric.AbstractLongStorage;
import org.enso.table.data.column.storage.numeric.BigDecimalStorage;
import org.enso.table.data.column.storage.numeric.BigIntegerStorage;
import org.enso.table.data.column.storage.numeric.DoubleStorage;
import org.enso.table.data.column.storage.type.AnyObjectType;
import org.graalvm.polyglot.Context;

public class ToBigIntegerConverter implements StorageConverter<BigInteger> {
  @Override
  public Storage<BigInteger> cast(Storage<?> storage, CastProblemAggregator problemAggregator) {
    if (storage instanceof BigIntegerStorage bigIntegerStorage) {
      return bigIntegerStorage;
    } else if (storage instanceof AbstractLongStorage longStorage) {
      return convertLongStorage(longStorage, problemAggregator);
    } else if (storage instanceof DoubleStorage doubleStorage) {
      return convertDoubleStorage(doubleStorage, problemAggregator);
    } else if (storage instanceof BoolStorage boolStorage) {
      return convertBoolStorage(boolStorage, problemAggregator);
    } else if (storage instanceof BigDecimalStorage bigDecimalStorage) {
      return convertBigDecimalStorage(bigDecimalStorage, problemAggregator);
    } else if (storage.getType() instanceof AnyObjectType) {
      return castFromMixed(storage, problemAggregator);
    } else {
      throw new IllegalStateException(
          "No known strategy for casting storage " + storage + " to BigInteger.");
    }
  }

  private Storage<BigInteger> convertDoubleStorage(
      DoubleStorage doubleStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    int n = doubleStorage.size();
    BigIntegerBuilder builder = new BigIntegerBuilder(n, problemAggregator);
    for (int i = 0; i < n; i++) {
      if (doubleStorage.isNothing(i)) {
        builder.appendNulls(1);
      } else {
        double x = doubleStorage.getItemAsDouble(i);
        BigInteger bigInteger = BigDecimal.valueOf(x).toBigInteger();
        builder.appendRawNoGrow(bigInteger);
      }

      context.safepoint();
    }
    return builder.seal();
  }

  private Storage<BigInteger> convertLongStorage(
      AbstractLongStorage longStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    int n = longStorage.size();
    BigIntegerBuilder builder = new BigIntegerBuilder(n, problemAggregator);
    for (int i = 0; i < n; i++) {
      if (longStorage.isNothing(i)) {
        builder.appendNulls(1);
      } else {
        long x = longStorage.getItem(i);
        BigInteger bigInteger = BigInteger.valueOf(x);
        builder.appendRawNoGrow(bigInteger);
      }

      context.safepoint();
    }
    return builder.seal();
  }

  private Storage<BigInteger> convertBoolStorage(
      BoolStorage boolStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    int n = boolStorage.size();
    BigIntegerBuilder builder = new BigIntegerBuilder(n, problemAggregator);
    for (int i = 0; i < n; i++) {
      if (boolStorage.isNothing(i)) {
        builder.appendNulls(1);
      } else {
        boolean x = boolStorage.getItem(i);
        BigInteger bigInteger = booleanAsBigInteger(x);
        builder.appendRawNoGrow(bigInteger);
      }

      context.safepoint();
    }
    return builder.seal();
  }

  private Storage<BigInteger> convertBigDecimalStorage(
      BigDecimalStorage bigDecimalStorage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    int n = bigDecimalStorage.size();
    BigIntegerBuilder builder = new BigIntegerBuilder(n, problemAggregator);
    for (int i = 0; i < n; i++) {
      BigDecimal value = bigDecimalStorage.getItemBoxed(i);
      if (value == null) {
        builder.appendNulls(1);
      } else {
        BigInteger bigInteger = value.toBigInteger();
        builder.appendRawNoGrow(bigInteger);
      }

      context.safepoint();
    }
    return builder.seal();
  }

  private Storage<BigInteger> castFromMixed(
      Storage<?> storage, CastProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    int n = storage.size();
    BigIntegerBuilder builder = new BigIntegerBuilder(n, problemAggregator);
    for (int i = 0; i < n; i++) {
      Object o = storage.getItemBoxed(i);
      switch (o) {
        case null -> builder.appendNulls(1);
        case Boolean b -> builder.appendRawNoGrow(booleanAsBigInteger(b));
        case Long l -> builder.appendRawNoGrow(BigInteger.valueOf(l));
        case Double d -> builder.appendRawNoGrow(BigDecimal.valueOf(d).toBigInteger());
        case BigInteger bigInteger -> builder.appendRawNoGrow(bigInteger);
        case BigDecimal bigDecimal -> builder.appendRawNoGrow(bigDecimal.toBigInteger());
        default -> {
          problemAggregator.reportConversionFailure(o);
          builder.appendNulls(1);
        }
      }

      context.safepoint();
    }
    return builder.seal();
  }

  public static BigInteger booleanAsBigInteger(boolean value) {
    return value ? BigInteger.ONE : BigInteger.ZERO;
  }
}
