package org.enso.interpreter.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.enso.common.MethodNames;
import org.enso.test.utils.ContextUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class LazyAtomFieldTest {
  private static final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private static Context ctx;

  @BeforeClass
  public static void prepareCtx() {
    ctx = ContextUtils.createDefaultContext(out);
  }

  @Before
  public void resetOut() {
    out.reset();
  }

  @AfterClass
  public static void disposeCtx() {
    ctx.close();
  }

  @Test
  public void evaluation() throws Exception {
    final String code =
        """
    from Standard.Base import IO

    type Lazy
        LazyValue ~x ~y

        say self w = "Hello " + w.to_text

        meaning self =
            IO.println "Computing meaning"
            v = self.x * self.y
            IO.println "Computed meaning"
            v

    meanings =
        compute_x =
            IO.println "Computing x"
            v = 6
            IO.println "Computing x done"
            v

        compute_y =
            IO.println "Computing y"
            v = 7
            IO.println "Computing y done"
            v

        IO.println "Start"
        l = Lazy.LazyValue compute_x compute_y
        IO.println "Lazy value ready"
        IO.println <| l.say "World!"
        IO.println l.meaning
        IO.println <| l.say "Again!"
        IO.println l.meaning
        l.meaning
    """;
    var meanings = evalCode(code, "meanings");
    assertEquals(42, meanings.asInt());

    String log = out.toString(StandardCharsets.UTF_8);
    var lazyReadyAndThen =
        log.lines().dropWhile(l -> l.contains("Lazy value ready")).collect(Collectors.toList());
    var computingX = lazyReadyAndThen.stream().filter(l -> l.contains("Computing x done")).count();
    assertEquals(log, 1, computingX);
    var computingY = lazyReadyAndThen.stream().filter(l -> l.contains("Computing y done")).count();
    assertEquals(log, 1, computingY);
    var hellos = lazyReadyAndThen.stream().filter(l -> l.startsWith("Hello")).count();
    assertEquals(log, 2, hellos);
  }

  @Test
  public void testInfiniteListGenerator() throws Exception {
    final String code =
        """
    import Standard.Base.IO

    type Lazy
        Nil
        Cons ~x ~xs

        take self n = if n == 0 then Lazy.Nil else case self of
            Lazy.Nil -> Lazy.Nil
            Lazy.Cons x xs -> Lazy.Cons x (xs.take n-1)

        sum self acc = case self of
            Lazy.Nil -> acc
            Lazy.Cons x xs -> @Tail_Call xs.sum acc+x

        generator n = Lazy.Cons n (Lazy.generator n+1)

    both n =
        g = Lazy.generator 1
        // IO.println "Generator is computed"
        t = g.take n
        // IO.println "Generator is taken"
        t . sum 0
    """;

    var both = evalCode(code, "both");
    var sum = both.execute(100);
    String log = out.toString(StandardCharsets.UTF_8);
    assertEquals(log, 5050, sum.asLong());
  }

  @Test
  public void fourAtomIntFields() throws Exception {
    checkNumHolder(
        """
    type Num
        Holder a b c ~num

        new  = Num.Holder 1 2 3 (R.new.nextInt)
    """);
  }

  @Test
  public void fourAtomObjectFields() throws Exception {
    checkNumHolder(
        """
    type Num
        Holder a b c ~num

        new  = Num.Holder "a" "b" "c" (R.new.nextInt)
    """);
  }

  @Test
  public void fiveAtomIntFields() throws Exception {
    checkNumHolder(
        """
    type Num
        Holder a b c d ~num

        new  = Num.Holder 1 2 3 4 (R.new.nextInt)
    """);
  }

  @Test
  public void fiveAtomObjectFields() throws Exception {
    checkNumHolder(
        """
    type Num
        Holder a b c d ~num

        new  = Num.Holder "a" "b" "c" "d" (R.new.nextInt)
    """);
  }

  private void checkNumHolder(String typeDefinition) throws Exception {
    var code =
        "polyglot java import java.util.Random as R\n"
            + typeDefinition
            + """

      create ignore =
        fbl = Num.new
        f = fbl.num
        n = fbl.num
        [ f, n ]
      """;
    var create = evalCode(code, "create");
    var tupple = create.execute(0);

    assertEquals("Two values", 2, tupple.getArraySize());
    var first = tupple.getArrayElement(0).asInt();
    var second = tupple.getArrayElement(1).asInt();

    assertEquals("Both numbers are the same", first, second);
  }

  private Value evalCode(final String code, final String methodName) throws URISyntaxException {
    final var testName = "test.enso";
    final URI testUri = new URI("memory://" + testName);
    final Source src = Source.newBuilder("enso", code, testName).uri(testUri).buildLiteral();
    var module = ctx.eval(src);
    return module.invokeMember(MethodNames.Module.EVAL_EXPRESSION, methodName);
  }
}
