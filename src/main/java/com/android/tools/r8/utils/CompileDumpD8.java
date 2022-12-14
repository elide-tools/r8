// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wrapper to make it easy to call D8 mode when compiling a dump file.
 *
 * <p>This wrapper will be added to the classpath so it *must* only refer to the public API. See
 * {@code tools/compiledump.py}.
 *
 * <p>It is tempting to have this class share the D8 parser code, but such refactoring would not be
 * valid on past version of the D8 API. Thus there is little else to do than reimplement the parts
 * we want to support for reading dumps.
 */
public class CompileDumpD8 {

  private static final List<String> VALID_OPTIONS =
      Arrays.asList(
          "--classfile",
          "--debug",
          "--release",
          "--enable-missing-library-api-modeling",
          "--android-platform-build");

  private static final List<String> VALID_OPTIONS_WITH_SINGLE_OPERAND =
      Arrays.asList(
          "--output",
          "--lib",
          "--classpath",
          "--min-api",
          "--main-dex-rules",
          "--main-dex-list",
          "--main-dex-list-output",
          "--desugared-lib",
          "--threads",
          "--startup-profile");

  public static void main(String[] args) throws CompilationFailedException {
    OutputMode outputMode = OutputMode.DexIndexed;
    Path outputPath = null;
    Path desugaredLibJson = null;
    CompilationMode compilationMode = CompilationMode.RELEASE;
    List<Path> program = new ArrayList<>();
    List<Path> library = new ArrayList<>();
    List<Path> classpath = new ArrayList<>();
    List<Path> mainDexRulesFiles = new ArrayList<>();
    List<Path> startupProfileFiles = new ArrayList<>();
    int minApi = 1;
    int threads = -1;
    boolean enableMissingLibraryApiModeling = false;
    boolean androidPlatformBuild = false;
    for (int i = 0; i < args.length; i++) {
      String option = args[i];
      if (VALID_OPTIONS.contains(option)) {
        switch (option) {
          case "--classfile":
            {
              outputMode = OutputMode.ClassFile;
              break;
            }
          case "--debug":
            {
              compilationMode = CompilationMode.DEBUG;
              break;
            }
          case "--release":
            {
              compilationMode = CompilationMode.RELEASE;
              break;
            }
          case "--enable-missing-library-api-modeling":
            enableMissingLibraryApiModeling = true;
            break;
          case "--android-platform-build":
            androidPlatformBuild = true;
            break;
          default:
            throw new IllegalArgumentException("Unimplemented option: " + option);
        }
      } else if (VALID_OPTIONS_WITH_SINGLE_OPERAND.contains(option)) {
        String operand = args[++i];
        switch (option) {
          case "--output":
            {
              outputPath = Paths.get(operand);
              break;
            }
          case "--lib":
            {
              library.add(Paths.get(operand));
              break;
            }
          case "--classpath":
            {
              classpath.add(Paths.get(operand));
              break;
            }
          case "--min-api":
            {
              minApi = Integer.parseInt(operand);
              break;
            }
          case "--desugared-lib":
            {
              desugaredLibJson = Paths.get(operand);
              break;
            }
          case "--threads":
            {
              threads = Integer.parseInt(operand);
              break;
            }
          case "--main-dex-rules":
            {
              mainDexRulesFiles.add(Paths.get(operand));
              break;
            }
          case "--startup-profile":
            {
              startupProfileFiles.add(Paths.get(operand));
              break;
            }
          default:
            throw new IllegalArgumentException("Unimplemented option: " + option);
        }
      } else {
        program.add(Paths.get(option));
      }
    }
    D8Command.Builder commandBuilder =
        D8Command.builder()
            .addProgramFiles(program)
            .addLibraryFiles(library)
            .addClasspathFiles(classpath)
            .addMainDexRulesFiles(mainDexRulesFiles)
            .setOutput(outputPath, outputMode)
            .setMode(compilationMode);
    getReflectiveBuilderMethod(
            commandBuilder, "setEnableExperimentalMissingLibraryApiModeling", boolean.class)
        .accept(new Object[] {enableMissingLibraryApiModeling});
    getReflectiveBuilderMethod(commandBuilder, "setAndroidPlatformBuild", boolean.class)
        .accept(new Object[] {androidPlatformBuild});
    getReflectiveBuilderMethod(commandBuilder, "addStartupProfileProviders", Collection.class)
        .accept(new Object[] {createStartupProfileProviders(startupProfileFiles)});
    if (desugaredLibJson != null) {
      commandBuilder.addDesugaredLibraryConfiguration(readAllBytesJava7(desugaredLibJson));
    }
    commandBuilder.setMinApiLevel(minApi);
    D8Command command = commandBuilder.build();
    if (threads != -1) {
      ExecutorService executor = Executors.newWorkStealingPool(threads);
      try {
        D8.run(command, executor);
      } finally {
        executor.shutdown();
      }
    } else {
      D8.run(command);
    }
  }

  private static Collection<Object> createStartupProfileProviders(List<Path> startupProfileFiles) {
    List<Object> startupProfileProviders = new ArrayList<>();
    for (Path startupProfileFile : startupProfileFiles) {
      boolean found =
          callReflectiveUtilsMethod(
              "createStartupProfileProviderFromDumpFile",
              new Class<?>[] {Path.class},
              fn -> startupProfileProviders.add(fn.apply(new Object[] {startupProfileFile})));
      if (!found) {
        System.out.println(
            "Unable to add startup profiles as input. "
                + "Method createStartupProfileProviderFromDumpFile() was not found.");
        break;
      }
    }
    return startupProfileProviders;
  }

  private static Consumer<Object[]> getReflectiveBuilderMethod(
      D8Command.Builder builder, String setter, Class<?>... parameters) {
    try {
      Method declaredMethod = D8Command.Builder.class.getMethod(setter, parameters);
      return args -> {
        try {
          declaredMethod.invoke(builder, args);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      };
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
      // The option is not available so we just return an empty consumer
      return args -> {};
    }
  }

  private static boolean callReflectiveUtilsMethod(
      String methodName, Class<?>[] parameters, Consumer<Function<Object[], Object>> fnConsumer) {
    Class<?> utilsClass;
    try {
      utilsClass = Class.forName("com.android.tools.r8.utils.CompileDumpUtils");
    } catch (ClassNotFoundException e) {
      return false;
    }

    Method declaredMethod;
    try {
      declaredMethod = utilsClass.getMethod(methodName, parameters);
    } catch (NoSuchMethodException e) {
      return false;
    }

    fnConsumer.accept(
        args -> {
          try {
            return declaredMethod.invoke(null, args);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return true;
  }

  // We cannot use StringResource since this class is added to the class path and has access only
  // to the public APIs.
  private static String readAllBytesJava7(Path filePath) {
    String content = "";

    try {
      content = new String(Files.readAllBytes(filePath));
    } catch (IOException e) {
      e.printStackTrace();
    }

    return content;
  }
}
