/* إعدادات بناء LAME 3.100 لأندرويد (بديل ملف configure) */
#ifndef AMR_LAME_CONFIG_H
#define AMR_LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_UNISTD_H 1
#define HAVE_LIMITS_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_MEMCPY 1
#define HAVE_STRCHR 1

#define PACKAGE "lame"
#define PACKAGE_VERSION "3.100"
#define VERSION "3.100"

#define SIZEOF_DOUBLE 8
#define SIZEOF_FLOAT 4
#define SIZEOF_INT 4
#define SIZEOF_SHORT 2
#define SIZEOF_UNSIGNED_INT 4
#define SIZEOF_UNSIGNED_SHORT 2
#define SIZEOF_LONG_LONG 8
#define SIZEOF_UNSIGNED_LONG_LONG 8
#if defined(__LP64__)
#define SIZEOF_LONG 8
#define SIZEOF_UNSIGNED_LONG 8
#else
#define SIZEOF_LONG 4
#define SIZEOF_UNSIGNED_LONG 4
#endif

#endif
