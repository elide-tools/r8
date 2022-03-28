// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.origin.Origin;
import java.io.InputStream;

/**
 * Interface to provide global synthetic information to the compiler.
 *
 * <p>The global synthetic information can only be obtained by consuming it from a previous
 * compilation unit for the same compiler version. See {@code GlobalSyntheticsConsumer}.
 */
@Keep
public interface GlobalSyntheticsResourceProvider {

  /** Get the origin of the global synthetics resource. */
  Origin getOrigin();

  /** Get the bytes of the global synthetics resource. */
  InputStream getByteStream() throws ResourceException;
}
