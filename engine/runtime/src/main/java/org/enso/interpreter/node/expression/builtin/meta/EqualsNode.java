package org.enso.interpreter.node.expression.builtin.meta;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import java.math.BigInteger;
import org.enso.interpreter.dsl.AcceptsError;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.node.expression.builtin.number.utils.BigIntegerOps;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.EnsoMultiValue;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.WarningsLibrary;
import org.enso.interpreter.runtime.number.EnsoBigInteger;
import org.enso.polyglot.common_utils.Core_Text_Utils;

@BuiltinMethod(
    type = "Any",
    name = "==",
    description =
        """
      Compares self with other object and returns True iff `self` is exactly the same as
      the other object, including all its transitively accessible properties or fields,
      False otherwise.

      Can handle arbitrary objects, including all foreign objects.

      Does not throw dataflow errors or panics.

      Note that this is different than `Meta.is_same_object`, which checks whether two
      references point to the same object on the heap. Moreover, `Meta.is_same_object`
      implies `Any.==` for all object with the exception of `Number.nan`.
      """)
@GenerateUncached
public abstract class EqualsNode extends Node {

  public static EqualsNode build() {
    return EqualsNodeGen.create();
  }

  public abstract boolean execute(@AcceptsError Object self, @AcceptsError Object right);

  @Specialization
  boolean equalsBoolBool(boolean self, boolean other) {
    return self == other;
  }

  @Specialization
  boolean equalsBoolDouble(boolean self, double other) {
    return false;
  }

  @Specialization
  boolean equalsBoolLong(boolean self, long other) {
    return false;
  }

  @Specialization
  boolean equalsBoolBigInt(boolean self, EnsoBigInteger other) {
    return false;
  }

  @Specialization
  boolean equalsBoolText(boolean self, Text other) {
    return false;
  }

  @Specialization
  boolean equalsBoolInterop(
      boolean self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop) {
    try {
      return self == interop.asBoolean(other);
    } catch (UnsupportedMessageException ex) {
      return false;
    }
  }

  @Specialization
  boolean equalsLongLong(long self, long other) {
    return self == other;
  }

  @Specialization
  boolean equalsLongBool(long self, boolean other) {
    return false;
  }

  @Specialization
  boolean equalsLongDouble(long self, double other) {
    return (double) self == other;
  }

  @Specialization
  boolean equalsLongText(long self, Text other) {
    return false;
  }

  @Specialization
  @TruffleBoundary
  boolean equalsLongBigInt(long self, EnsoBigInteger other) {
    if (BigIntegerOps.fitsInLong(other.getValue())) {
      return BigInteger.valueOf(self).compareTo(other.getValue()) == 0;
    } else {
      return false;
    }
  }

  @Specialization
  boolean equalsLongInterop(
      long self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop) {
    try {
      return self == interop.asLong(other);
    } catch (UnsupportedMessageException ex) {
      return false;
    }
  }

  @Specialization
  boolean equalsDoubleDouble(double self, double other) {
    if (Double.isNaN(self) || Double.isNaN(other)) {
      return false;
    } else {
      return self == other;
    }
  }

  @Specialization
  boolean equalsDoubleLong(double self, long other) {
    return self == (double) other;
  }

  @Specialization
  boolean equalsDoubleBool(double self, boolean other) {
    return false;
  }

  @Specialization
  boolean equalsDoubleText(double self, Text other) {
    return false;
  }

  @Specialization
  boolean equalsDoubleInterop(
      double self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop) {
    try {
      if (interop.fitsInDouble(other)) {
        return self == interop.asDouble(other);
      }
      var otherBig = interop.asBigInteger(other);
      return self == asDouble(otherBig);
    } catch (UnsupportedMessageException ex) {
      return false;
    }
  }

  @TruffleBoundary
  private static double asDouble(BigInteger big) {
    return big.doubleValue();
  }

  @Specialization(guards = {"isBigInteger(iop, self)", "isBigInteger(iop, other)"})
  @TruffleBoundary
  boolean other(
      Object self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary iop) {
    return asBigInteger(iop, self).equals(asBigInteger(iop, other));
  }

  @Specialization(guards = "isBigInteger(iop, self)")
  @TruffleBoundary
  boolean equalsBigIntDouble(
      Object self,
      double other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary iop) {
    return asBigInteger(iop, self).doubleValue() == other;
  }

  @Specialization(guards = "isBigInteger(iop, self)")
  @TruffleBoundary
  boolean equalsBigIntLong(
      Object self, long other, @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary iop) {
    var v = asBigInteger(iop, self);
    if (BigIntegerOps.fitsInLong(v)) {
      return v.compareTo(BigInteger.valueOf(other)) == 0;
    } else {
      return false;
    }
  }

  @Specialization
  boolean equalsBigIntBool(EnsoBigInteger self, boolean other) {
    return false;
  }

  @Specialization
  boolean equalsBigIntText(EnsoBigInteger self, Text other) {
    return false;
  }

  @TruffleBoundary
  @Specialization(guards = {"isBigInteger(iop, self)", "!isPrimitiveValue(other)"})
  boolean equalsBigIntInterop(
      Object self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary iop) {
    try {
      var otherBigInteger = InteropLibrary.getUncached().asBigInteger(other);
      return asBigInteger(iop, self).equals(otherBigInteger);
    } catch (UnsupportedMessageException ex) {
      return false;
    }
  }

  @Specialization(guards = {"selfText.is_normalized()", "otherText.is_normalized()"})
  boolean equalsTextText(Text selfText, Text otherText) {
    return selfText.toString().compareTo(otherText.toString()) == 0;
  }

  @Specialization
  boolean equalsTextBool(Text self, boolean other) {
    return false;
  }

  @Specialization
  boolean equalsTextLong(Text selfText, long otherLong) {
    return false;
  }

  @Specialization
  boolean equalsTextDouble(Text selfText, double otherDouble) {
    return false;
  }

  @Specialization
  boolean equalsTextBigInt(Text self, EnsoBigInteger other) {
    return false;
  }

  /**
   * Compares interop string with other object. If the other object doesn't support conversion to
   * String, it is not equal. Otherwise the two strings are compared according to the
   * lexicographical order, handling Unicode normalization. See {@code Text_Utils.compare_to}.
   */
  @TruffleBoundary
  @Specialization(
      guards = {"selfInterop.isString(selfString)"},
      limit = "3")
  boolean equalsStrings(
      Object selfString,
      Object otherString,
      @CachedLibrary("selfString") InteropLibrary selfInterop,
      @CachedLibrary("otherString") InteropLibrary otherInterop) {
    String selfJavaString;
    String otherJavaString;
    try {
      selfJavaString = selfInterop.asString(selfString);
      otherJavaString = otherInterop.asString(otherString);
    } catch (UnsupportedMessageException e) {
      return false;
    }
    return Core_Text_Utils.equals(selfJavaString, otherJavaString);
  }

  @Specialization(guards = "isPrimitive(self, interop) != isPrimitive(other, interop)")
  boolean equalsDifferent(
      Object self,
      Object other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop) {
    return false;
  }

  /** Equals for Atoms and AtomConstructors */
  @Specialization
  boolean equalsAtomConstructors(AtomConstructor self, AtomConstructor other) {
    return self == other;
  }

  @Specialization
  boolean equalsAtoms(
      Atom self,
      Atom other,
      @Cached EqualsAtomNode equalsAtomNode,
      @Shared("isSameObjectNode") @Cached IsSameObjectNode isSameObjectNode) {
    return isSameObjectNode.execute(self, other) || equalsAtomNode.execute(self, other);
  }

  @Specialization
  boolean equalsReverseBoolean(
      TruffleObject self,
      boolean other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("reverse") @Cached EqualsNode reverse) {
    return reverse.execute(other, self);
  }

  @Specialization
  boolean equalsReverseLong(
      TruffleObject self,
      long other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("reverse") @Cached EqualsNode reverse) {
    return reverse.execute(other, self);
  }

  @Specialization
  boolean equalsReverseDouble(
      TruffleObject self,
      double other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("reverse") @Cached EqualsNode reverse) {
    return reverse.execute(other, self);
  }

  @Specialization
  boolean equalsReverseBigInt(
      TruffleObject self,
      EnsoBigInteger other,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop,
      @Shared("reverse") @Cached EqualsNode reverse) {
    return reverse.execute(other, self);
  }

  @Specialization(guards = "isNotPrimitive(self, other, interop, warnings)")
  boolean equalsComplex(
      Object self,
      Object other,
      @Cached EqualsComplexNode equalsComplex,
      @Shared("isSameObjectNode") @Cached IsSameObjectNode isSameObjectNode,
      @Shared("interop") @CachedLibrary(limit = "10") InteropLibrary interop,
      @CachedLibrary(limit = "5") WarningsLibrary warnings) {
    return isSameObjectNode.execute(self, other) || equalsComplex.execute(self, other);
  }

  static boolean isNotPrimitive(
      Object a, Object b, InteropLibrary interop, WarningsLibrary warnings) {
    if (a instanceof AtomConstructor && b instanceof AtomConstructor) {
      return false;
    }
    if (a instanceof Atom && b instanceof Atom) {
      return false;
    }
    if (warnings.hasWarnings(a) || warnings.hasWarnings(b)) {
      return true;
    }
    if (a instanceof EnsoMultiValue || b instanceof EnsoMultiValue) {
      return true;
    }
    return !isPrimitive(a, interop) && !isPrimitive(b, interop);
  }

  /**
   * Return true iff object is a primitive value used in some specializations guard. By primitive
   * value we mean any value that can be present in Enso, so, for example, not Integer, as that
   * cannot be present in Enso. All the primitive types should be handled in their corresponding
   * specializations. See {@link
   * org.enso.interpreter.node.expression.builtin.interop.syntax.HostValueToEnsoNode}.
   */
  static boolean isPrimitive(Object object, InteropLibrary interop) {
    return isPrimitiveValue(object)
        || object instanceof EnsoBigInteger
        || object instanceof Text
        || interop.isString(object)
        || interop.isNumber(object)
        || interop.isBoolean(object);
  }

  static boolean isPrimitiveValue(Object object) {
    return object instanceof Boolean || object instanceof Long || object instanceof Double;
  }

  static boolean isBigInteger(InteropLibrary iop, Object v) {
    if (v instanceof EnsoBigInteger) {
      return true;
    } else {
      return !iop.fitsInDouble(v) && !iop.fitsInLong(v) && iop.fitsInBigInteger(v);
    }
  }

  BigInteger asBigInteger(InteropLibrary iop, Object v) {
    if (v instanceof EnsoBigInteger big) {
      return big.getValue();
    } else {
      try {
        return iop.asBigInteger(v);
      } catch (UnsupportedMessageException ex) {
        throw EnsoContext.get(this).raiseAssertionPanic(this, "Expecting BigInteger", ex);
      }
    }
  }
}
