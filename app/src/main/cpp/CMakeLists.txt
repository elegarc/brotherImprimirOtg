cmake_minimum_required(VERSION 3.22.1)

project("brlaser_driver")

add_library(
    brlaser_driver
    SHARED
    brlaser_bridge.cpp
)

find_library(
    log-lib
    log
)

find_library(
    jnigraphics-lib
    jnigraphics
)

target_link_libraries(
    brlaser_driver
    ${log-lib}
    ${jnigraphics-lib}
)
