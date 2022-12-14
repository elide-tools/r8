// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.diagnostics;

import static com.android.tools.r8.DiagnosticsMatcher.diagnosticMessage;
import static com.android.tools.r8.DiagnosticsMatcher.diagnosticType;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.startsWith;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsMatcher;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.errors.UnsupportedFeatureDiagnostic;
import com.android.tools.r8.utils.AndroidApiLevel;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UnsupportedFeaturesDiagnosticsTest extends TestBase {

  // Hard coded messages in AGP. See D8DexArchiveBuilder.

  private static final String AGP_INVOKE_CUSTOM =
      "Invoke-customs are only supported starting with Android O";

  private static final String AGP_DEFAULT_INTERFACE_METHOD =
      "Default interface methods are only supported starting with Android N (--min-api 24)";

  private static final String AGP_STATIC_INTERFACE_METHOD =
      "Static interface methods are only supported starting with Android N (--min-api 24)";

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  private static class FeatureMatcher extends DiagnosticsMatcher {
    private final String descriptor;

    public FeatureMatcher(String descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    protected boolean eval(Diagnostic diagnostic) {
      return ((UnsupportedFeatureDiagnostic) diagnostic).getFeatureDescriptor().equals(descriptor);
    }

    @Override
    protected void explain(Description description) {
      description.appendText("feature ").appendText(descriptor);
    }
  }

  public static Matcher<Diagnostic> matches(String descriptor) {
    return allOf(
        diagnosticType(UnsupportedFeatureDiagnostic.class), new FeatureMatcher(descriptor));
  }

  public UnsupportedFeaturesDiagnosticsTest(TestParameters parameters) {
    parameters.assertNoneRuntime();
  }

  @Test
  public void testInvokeLambdaMetafactory() throws Exception {
    testForD8()
        .addProgramClassesAndInnerClasses(LambdaMetafactoryTest.class)
        .setMinApi(AndroidApiLevel.B)
        .disableDesugaring()
        .compileWithExpectedDiagnostics(
            diagnostics -> {
              diagnostics
                  .assertOnlyWarnings()
                  .assertWarningsMatch(
                      allOf(
                          matches("invoke-custom"),
                          diagnosticMessage(startsWith(AGP_INVOKE_CUSTOM))));
            });
  }

  @Test(expected = CompilationFailedException.class)
  public void testDefaultInterfaceMethods() throws Exception {
    testForD8()
        .addProgramClassesAndInnerClasses(DefaultInterfaceMethodsTest.class)
        .setMinApi(AndroidApiLevel.B)
        .disableDesugaring()
        .compileWithExpectedDiagnostics(
            diagnostics -> {
              diagnostics
                  .assertOnlyErrors()
                  .assertErrorsMatch(
                      allOf(
                          matches("default-interface-method"),
                          diagnosticMessage(startsWith(AGP_DEFAULT_INTERFACE_METHOD))));
            });
  }

  @Test(expected = CompilationFailedException.class)
  public void testStaticInterfaceMethods() throws Exception {
    testForD8()
        .addProgramClassesAndInnerClasses(StaticInterfaceMethodsTest.class)
        .setMinApi(AndroidApiLevel.B)
        .disableDesugaring()
        .compileWithExpectedDiagnostics(
            diagnostics -> {
              diagnostics
                  .assertOnlyErrors()
                  .assertErrorsMatch(
                      allOf(
                          matches("static-interface-method"),
                          diagnosticMessage(startsWith(AGP_STATIC_INTERFACE_METHOD))));
            });
  }

  static class LambdaMetafactoryTest {

    public interface StringSupplier {
      String get();
    }

    public static void print(StringSupplier supplier) {
      System.out.println(supplier.get());
    }

    public static void main(String[] args) {
      print(() -> "Hello, world");
    }
  }

  static class DefaultInterfaceMethodsTest {

    interface WithDefaults {
      default void foo() {
        System.out.println("WithDefaults::foo");
      }
    }

    static class MyClass implements WithDefaults {}

    public static void main(String[] args) {
      new MyClass().foo();
    }
  }

  static class StaticInterfaceMethodsTest {

    interface WithStatics {
      static void foo() {
        System.out.println("WithStatics::foo");
      }
    }

    public static void main(String[] args) {
      WithStatics.foo();
    }
  }
}
