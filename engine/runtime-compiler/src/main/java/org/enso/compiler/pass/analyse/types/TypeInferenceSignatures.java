package org.enso.compiler.pass.analyse.types;

import java.util.List;
import java.util.UUID;
import org.enso.compiler.context.InlineContext;
import org.enso.compiler.context.ModuleContext;
import org.enso.compiler.core.ir.Expression;
import org.enso.compiler.core.ir.Function;
import org.enso.compiler.core.ir.Module;
import org.enso.compiler.core.ir.Name;
import org.enso.compiler.core.ir.module.scope.Definition;
import org.enso.compiler.core.ir.module.scope.definition.Method;
import org.enso.compiler.pass.IRPass;
import org.enso.compiler.pass.analyse.BindingAnalysis$;
import org.enso.compiler.pass.resolve.FullyQualifiedNames$;
import org.enso.compiler.pass.resolve.GlobalNames$;
import org.enso.compiler.pass.resolve.Patterns$;
import org.enso.compiler.pass.resolve.TypeNames$;
import org.enso.compiler.pass.resolve.TypeSignatures$;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.immutable.Seq;
import scala.collection.immutable.Seq$;
import scala.jdk.javaapi.CollectionConverters;
import scala.jdk.javaapi.CollectionConverters$;

/**
 * A precursor pass that prepares the IR for type inference, run before the main propagation logic
 * runs in {@link TypeInferencePropagation}.
 *
 * <p>It handles storing inferred types based on signatures of the top-level bindings. This is done
 * as a separate pass, to ensure that once propagation runs, all top-level bindings that have a
 * signature, already have an inferred type assigned to them, so that these types can be used. This
 * makes the job of the propagation pass much easier, avoiding it to deal with ensuring in what
 * order the types are inferred, and ensuring that even without a more complicated unification logic
 * that will be needed for recursive definitions, many types can already be inferred. In the future,
 * it may be possible that this pass will no longer be needed - if the propagation pass will be
 * smart enough to deal with the unknowns. But for now it gives us a very big gain very quickly - as
 * most of our standard library is annotated with type signatures, we will be able to benefit from
 * these right away, without needing to implement more complicated recursive inference logic.
 *
 * <p>This pass is very simple - it looks at ascribed types of the top level bindings to find out
 * expected types of each function's arguments, and looks at the outer-most expression of the
 * binding in search for a return-type ascription (these ascriptions are inserted by the return-type
 * ascription translation in {@code addTypeAscription} within {@link
 * org.enso.compiler.core.TreeToIr}). It does not look any deeper into the expressions, ensuring
 * that it is relatively quick to run.
 */
public class TypeInferenceSignatures implements IRPass {
  public static final TypeInferenceSignatures INSTANCE = new TypeInferenceSignatures();
  private static final Logger logger = LoggerFactory.getLogger(TypeInferenceSignatures.class);
  private UUID uuid;
  private final TypeResolver typeResolver = new TypeResolver();

  @Override
  public void org$enso$compiler$pass$IRPass$_setter_$key_$eq(UUID v) {
    this.uuid = v;
  }

  @Override
  public UUID key() {
    return uuid;
  }

  @Override
  public Seq<IRPass> precursorPasses() {
    List<IRPass> passes =
        List.of(
            BindingAnalysis$.MODULE$,
            GlobalNames$.MODULE$,
            FullyQualifiedNames$.MODULE$,
            TypeNames$.MODULE$,
            Patterns$.MODULE$,
            TypeSignatures$.MODULE$);
    return CollectionConverters.asScala(passes).toList();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Seq<IRPass> invalidatedPasses() {
    return (Seq<IRPass>) Seq$.MODULE$.empty();
  }

  @Override
  public Module runModule(Module ir, ModuleContext moduleContext) {
    ir.bindings()
        .foreach(
            (def) ->
                switch (def) {
                  case Method.Explicit b -> {
                    TypeRepresentation resolvedType = resolveTopLevelTypeSignature(b.body());
                    if (resolvedType != null) {
                      System.out.println(
                          "Resolved "
                              + b.methodReference().showCode()
                              + " to type "
                              + resolvedType);
                      // TODO maybe different metadata class?
                      // TODO use this in TypeInferencePropagation if its own metadata is not yet
                      // available
                      ir.passData().update(INSTANCE, new InferredType(resolvedType));
                    }
                    yield b;
                  }
                  case Definition.Type typ -> typ;
                  default -> {
                    logger.trace("UNEXPECTED definition {}", def.getClass().getCanonicalName());
                    yield def;
                  }
                });
    return ir;
  }

  @Override
  public Expression runExpression(Expression ir, InlineContext inlineContext) {
    // This pass does not do anything when run on expressions. It only processes top-level bindings.
    return ir;
  }

  private TypeRepresentation resolveTopLevelTypeSignature(Expression expression) {
    return switch (expression) {
        // Combine argument types with ascribed type (if available) for a function type signature
      case Function.Lambda lambda -> {
        boolean hasAnyDefaults =
            lambda.arguments().find((arg) -> arg.defaultValue().isDefined()).isDefined();
        if (hasAnyDefaults) {
          // TODO inferring types that have default arguments
          yield null;
        }

        scala.collection.immutable.List<TypeRepresentation> argTypesScala =
            lambda
                .arguments()
                .filter((arg) -> !(arg.name() instanceof Name.Self))
                .map(
                    (arg) -> {
                      if (arg.ascribedType().isDefined()) {
                        Expression typeExpression = arg.ascribedType().get();
                        var resolvedTyp = typeResolver.resolveTypeExpression(typeExpression);
                        if (resolvedTyp != null) {
                          return resolvedTyp;
                        }
                      }

                      return TypeRepresentation.UNKNOWN;
                    });

        TypeRepresentation ascribedReturnType = typeResolver.findTypeAscription(lambda.body());

        if (ascribedReturnType == null && argTypesScala.isEmpty()) {
          // If we did not infer return type NOR arity, we know nothing useful about this function, so we withdraw.
          yield null;
        }

        TypeRepresentation returnType =
            ascribedReturnType != null ? ascribedReturnType : TypeRepresentation.UNKNOWN;
        yield TypeRepresentation.buildFunction(
            CollectionConverters$.MODULE$.asJava(argTypesScala), returnType);
      }

        // Otherwise, we encountered a 0-argument method, so its type is just its return type (if
        // its known).
      default -> typeResolver.findTypeAscription(expression);
    };
  }
}
