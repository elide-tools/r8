// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.desugaredlibrary.conversiontests;

import static com.android.tools.r8.desugar.desugaredlibrary.test.CompilationSpecification.DEFAULT_SPECIFICATIONS;
import static com.android.tools.r8.desugar.desugaredlibrary.test.LibraryDesugaringSpecification.getJdk8Jdk11;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.desugar.desugaredlibrary.DesugaredLibraryTestBase;
import com.android.tools.r8.desugar.desugaredlibrary.test.CompilationSpecification;
import com.android.tools.r8.desugar.desugaredlibrary.test.CustomLibrarySpecification;
import com.android.tools.r8.desugar.desugaredlibrary.test.LibraryDesugaringSpecification;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.StringUtils;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TryCatchTimeConversionTest extends DesugaredLibraryTestBase {

  private final TestParameters parameters;
  private final LibraryDesugaringSpecification libraryDesugaringSpecification;
  private final CompilationSpecification compilationSpecification;

  private static final AndroidApiLevel MIN_SUPPORTED = AndroidApiLevel.O;
  private static final String EXPECTED_RESULT =
      StringUtils.lines("GMT", "GMT", "GMT", "GMT", "GMT");
  private static final String EXPECTED_RESULT_EXCEPTION =
      StringUtils.lines("GMT", "GMT", "GMT", "GMT", "GMT", "Exception caught");

  @Parameters(name = "{0}, spec: {1}, {2}")
  public static List<Object[]> data() {
    return buildParameters(
        getConversionParametersUpToExcluding(MIN_SUPPORTED),
        getJdk8Jdk11(),
        DEFAULT_SPECIFICATIONS);
  }

  public TryCatchTimeConversionTest(
      TestParameters parameters,
      LibraryDesugaringSpecification libraryDesugaringSpecification,
      CompilationSpecification compilationSpecification) {
    this.parameters = parameters;
    this.libraryDesugaringSpecification = libraryDesugaringSpecification;
    this.compilationSpecification = compilationSpecification;
  }

  @Test
  public void testBaseline() throws Exception {
    testForDesugaredLibrary(parameters, libraryDesugaringSpecification, compilationSpecification)
        .addProgramClasses(BaselineExecutor.class)
        .setCustomLibrarySpecification(
            new CustomLibrarySpecification(CustomLibClass.class, MIN_SUPPORTED))
        .addKeepMainRule(BaselineExecutor.class)
        .run(parameters.getRuntime(), BaselineExecutor.class)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testTryCatch() throws Exception {
    testForDesugaredLibrary(parameters, libraryDesugaringSpecification, compilationSpecification)
        .addProgramClasses(TryCatchExecutor.class)
        .setCustomLibrarySpecification(
            new CustomLibrarySpecification(CustomLibClass.class, MIN_SUPPORTED))
        .addKeepMainRule(TryCatchExecutor.class)
        .run(parameters.getRuntime(), TryCatchExecutor.class)
        .assertSuccessWithOutput(EXPECTED_RESULT_EXCEPTION);
  }
  @SuppressWarnings("WeakerAccess")
  static class BaselineExecutor {

    private static final String ZONE_ID = "GMT";

    public static void main(String[] args) {
      returnOnly();
      oneParameter();
      twoParameters();
      oneParameterReturn();
      twoParametersReturn();
    }

    public static void returnOnly() {
      ZoneId returnOnly = CustomLibClass.returnOnly();
      System.out.println(returnOnly.getId());
    }

    public static void oneParameterReturn() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId oneParam = CustomLibClass.oneParameterReturn(z1);
      System.out.println(oneParam.getId());
    }

    public static void twoParametersReturn() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId z2 = ZoneId.of(ZONE_ID);
      ZoneId twoParam = CustomLibClass.twoParametersReturn(z1, z2);
      System.out.println(twoParam.getId());
    }

    public static void oneParameter() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      String res = CustomLibClass.oneParameter(z1);
      System.out.println(res);
    }

    public static void twoParameters() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId z2 = ZoneId.of(ZONE_ID);
      String res = CustomLibClass.twoParameters(z1, z2);
      System.out.println(res);
    }
  }

  @SuppressWarnings("WeakerAccess")
  static class TryCatchExecutor {

    private static final String ZONE_ID = "GMT";

    public static void main(String[] args) {
      returnOnly();
      oneParameter();
      twoParameters();
      oneParameterReturn();
      twoParametersReturn();
      twoParametersThrow();
    }

    public static void returnOnly() {
      ZoneId returnOnly;
      try {
        returnOnly = CustomLibClass.returnOnly();
      } catch (Exception e) {
        throw new RuntimeException("Test failed.");
      }
      System.out.println(returnOnly.getId());
    }

    public static void oneParameterReturn() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId oneParam;
      try {
        oneParam = CustomLibClass.oneParameterReturn(z1);
      } catch (Exception e) {
        throw new RuntimeException("Test failed.");
      }
      System.out.println(oneParam.getId());
    }

    public static void twoParametersReturn() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId z2 = ZoneId.of(ZONE_ID);
      ZoneId twoParam;
      try {
        twoParam = CustomLibClass.twoParametersReturn(z1, z2);
      } catch (Exception e) {
        throw new RuntimeException("Test failed.");
      }
      System.out.println(twoParam.getId());
    }

    public static void oneParameter() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      String res;
      try {
        res = CustomLibClass.oneParameter(z1);
      } catch (Exception e) {
        throw new RuntimeException("Test failed.");
      }
      System.out.println(res);
    }

    public static void twoParameters() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId z2 = ZoneId.of(ZONE_ID);
      String res;
      try {
        res = CustomLibClass.twoParameters(z1, z2);
      } catch (Exception e) {
        throw new RuntimeException("Test failed.");
      }
      System.out.println(res);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void twoParametersThrow() {
      ZoneId z1 = ZoneId.of(ZONE_ID);
      ZoneId z2 = ZoneId.of(ZONE_ID);
      try {
        CustomLibClass.twoParametersThrow(z1, z2);
        throw new RuntimeException("Test failed.");
      } catch (ArithmeticException e) {
        System.out.println("Exception caught");
      }
    }
  }

  // This class will be put at compilation time as library and on the runtime class path.
  // This class is convenient for easy testing. Each method plays the role of methods in the
  // platform APIs for which argument/return values need conversion.
  @SuppressWarnings("WeakerAccess")
  static class CustomLibClass {

    private static final String ZONE_ID = "GMT";

    public static ZoneId returnOnly() {
      return ZoneId.of(ZONE_ID);
    }

    public static ZoneId oneParameterReturn(ZoneId z1) {
      return z1;
    }

    public static ZoneId twoParametersReturn(ZoneId z1, ZoneId z2) {
      return z1;
    }

    public static String oneParameter(ZoneId z1) {
      return z1.getId();
    }

    public static String twoParameters(ZoneId z1, ZoneId z2) {
      return z1.getId();
    }

    @SuppressWarnings({"divzero", "NumericOverflow", "UnusedReturnValue"})
    public static String twoParametersThrow(ZoneId z1, ZoneId z2) {
      return "" + (1 / 0);
    }
  }
}
