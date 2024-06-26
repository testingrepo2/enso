package org.enso.test.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.enso.compiler.context.CompilerContext.Module;
import org.enso.compiler.data.BindingsMap.DefinedEntity;
import org.enso.compiler.data.BindingsMap.ResolvedName;
import org.graalvm.polyglot.Context;
import scala.jdk.javaapi.CollectionConverters;

/** Helper utility methods for manipulating with {@link org.enso.interpreter.runtime.Module}. */
public class ModuleUtils {
  private ModuleUtils() {}

  /**
   * Returns mapping of symbols to exported resolved names from the given module.
   *
   * @param modName FQN of the module
   * @see {@link BindingsMap#exportedSymbols()}
   */
  public static Map<String, List<ResolvedName>> getExportedSymbolsFromModule(
      Context ctx, String modName) {
    var ensoCtx = ContextUtils.leakContext(ctx);
    var mod = ensoCtx.getPackageRepository().getLoadedModule(modName).get();
    return getExportedSymbols(mod);
  }

  public static List<DefinedEntity> getDefinedEntities(Context ctx, String modName) {
    var ensoCtx = ContextUtils.leakContext(ctx);
    var mod = ensoCtx.getPackageRepository().getLoadedModule(modName).get();
    return CollectionConverters.asJava(mod.getBindingsMap().definedEntities());
  }

  private static Map<String, List<ResolvedName>> getExportedSymbols(Module module) {
    var bindings = new HashMap<String, List<ResolvedName>>();
    var bindingsScala = module.getBindingsMap().exportedSymbols();
    bindingsScala.foreach(
        entry -> {
          var symbol = entry._1;
          var resolvedNames = CollectionConverters.asJava(entry._2.toSeq());
          bindings.put(symbol, resolvedNames);
          return null;
        });
    return bindings;
  }
}