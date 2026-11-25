cmake_minimum_required(VERSION 3.18.1)
project(amrlame C)

# مصدر LAME بينزل تلقائياً قبل هالخطوة (مهمة fetchLame بـ app/build.gradle.kts)
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)
file(GLOB LAME_SRC ${LAME_DIR}/*.c)
list(REMOVE_ITEM LAME_SRC ${LAME_DIR}/mpglib_interface.c)   # فك التشفير غير مطلوب

add_library(amrlame SHARED amrlame_jni.c ${LAME_SRC})

# config.h بجانب هالملف، ومصدر LAME وlame.h بمجلد lame/
target_include_directories(amrlame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${LAME_DIR})
target_compile_definitions(amrlame PRIVATE HAVE_CONFIG_H=1 LAME_LIBRARY_BUILD=1 NDEBUG=1)
target_compile_options(amrlame PRIVATE -O2 -w)
target_link_options(amrlame PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(amrlame m log)
