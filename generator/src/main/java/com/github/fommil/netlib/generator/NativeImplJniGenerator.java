package com.github.fommil.netlib.generator;

import com.google.common.collect.Lists;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Mojo(
    name = "native-jni",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
public class NativeImplJniGenerator extends AbstractNetlibGenerator {

  protected final STGroupFile jniTemplates = new STGroupFile("com/github/fommil/netlib/generator/netlib-jni.stg", '$', '$');

  /**
   * The interface that we are implementing.
   */
  @Parameter(required = true)
  protected String implementing;

  /**
   * C Header files to include
   */
  @Parameter
  protected List<String> includes;

  /**
   * Prepended to the native function name.
   */
  @Parameter
  protected String prefix = "";

  /**
   * Suffixed to the native function name.
   */
  @Parameter
  protected String suffix = "";

  /**
   * Prepended to the native function parameter list.
   */
  @Parameter
  protected String firstParam;

  @Parameter
  protected String noFirstParam;

  @Parameter
  protected boolean cblas_hack;

  @Parameter
  protected boolean lapack_hack;

  @Override
  protected String generate(List<Method> methods) throws Exception {
    ST t = jniTemplates.getInstanceOf("jni");

    if (includes == null)
      includes = Lists.newArrayList();

    includes.add(outputName.replace(".c", ".h"));
    t.add("includes", includes);

    List<String> members = Lists.newArrayList();
    for (Method method : methods) {
      ST f = jniTemplates.getInstanceOf("function");
      f.add("returns", jType2C(method.getReturnType()));
      f.add("fqn", (implementing + "." + method.getName()).replace(".", "_"));
      f.add("name", prefix + method.getName() + suffix);
      List<String> params = getNetlibCParameterTypes(method);
      List<String> names = getNetlibJavaParameterNames(method);
      f.add("paramTypes", params);
      f.add("paramNames", names);
      f.add("params", getCMethodParams(method));

      if (method.getReturnType() == Void.TYPE) {
        f.add("assignReturn", "");
        f.add("return", "");
      } else {
        f.add("assignReturn", jType2C(method.getReturnType()) + " returnValue = ");
        f.add("return", "return returnValue;");
      }

      List<String> init = Lists.newArrayList();
      List<String> clean = Lists.newArrayList();

      for (int i = 0; i < params.size(); i++) {
        String param = params.get(i);
        String name = names.get(i);
        ST before = jniTemplates.getInstanceOf(param + "_init");
        if (before != null) {
          before.add("name", name);
          init.add(before.render());
        }

        ST after = jniTemplates.getInstanceOf(param + "_clean");
        if (after != null) {
          after.add("name", name);
          clean.add(after.render());
        }
      }
      Collections.reverse(clean);

      f.add("init", init);
      f.add("clean", clean);
      members.add(f.render());
    }


    t.add("members", members);

    return t.render();
  }

  private List<String> getNetlibCParameterTypes(Method method) {
    final List<String> types = Lists.newArrayList();
    iterateRelevantParameters(method, new ParameterCallback() {
      @Override
      public void process(int i, Class<?> param, String name) {
        types.add(jType2C(param));
      }
    });
    return types;
  }

  private String jType2C(Class param) {
    if (param == Void.TYPE)
      return "void";
    if (param.isArray())
      return "j" + param.getComponentType().getSimpleName() + "Array";
    return "j" + param.getSimpleName().toLowerCase();
  }

  private List<String> getCMethodParams(Method method) {
    final LinkedList<String> params = Lists.newLinkedList();
    if (firstParam != null && !method.getName().matches(noFirstParam)) {
      params.add(firstParam);
    }

    iterateRelevantParameters(method, new ParameterCallback() {
      @Override
      public void process(int i, Class<?> param, String name) {
        if (!param.isPrimitive()) {
          name = "jni_" + name;
          if (param.getSimpleName().endsWith("W")) {
            name = "&" + name;
          }
        }

        // TODO: clean up the hacks
        if (param == String.class) {
          if (cblas_hack) {
            if (name.contains("trans"))
              name = "getCblasTrans(" + name + ")";
            else if (name.contains("uplo"))
              name = "getCblasUpLo(" + name + ")";
            else if (name.contains("side"))
              name = "getCblasSide(" + name + ")";
            else if (name.contains("diag"))
              name = "getCblasDiag(" + name + ")";
          } else if (lapack_hack) {
            if (name.contains("trans") || name.contains("uplo") ||
                name.contains("side") || name.contains("diag") ||
                name.contains("compq"))
              name = name + "[0]";
          }
        }

        params.add(name);
      }
    });

    return params;
  }
}
