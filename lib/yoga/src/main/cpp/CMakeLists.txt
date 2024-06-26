#
# Copyright (c) 2014-present, Facebook, Inc.
# All rights reserved.
#
# This source code is licensed under the BSD-style license found in the
# LICENSE file in the root directory of this source tree. An additional grant
# of patent rights can be found in the PATENTS file in the same directory.
#

cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_VERBOSE_MAKEFILE on)

add_compile_options(
    -fno-omit-frame-pointer
    -fexceptions
    -Wall
    -std=c++20
    -O3)

file(GLOB_RECURSE yogacore_SRC yoga/*.cpp)
add_library(yogacore STATIC ${yogacore_SRC})

target_link_libraries(yogacore android log)
target_include_directories(yogacore PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR})
