// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.Keep;

/** Base interface for diagnostics related to proguard keep rules. */
@Keep
public interface ProguardKeepRuleDiagnostic extends Diagnostic {}
