// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package j$.nio.file.attribute;

public interface FileAttribute<T> {

  String name();

  T value();
}
