cmake_minimum_required(VERSION 3.22.1)
project(spdf_pdfium CXX)
add_library(spdf_pdfium SHARED pdfium_bridge.cpp)
target_link_libraries(spdf_pdfium dl jnigraphics)
