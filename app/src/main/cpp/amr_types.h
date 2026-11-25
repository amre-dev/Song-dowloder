/* أنواع IEEE754 اللي بيحتاجها LAME (util.h) وما بتتعرّف بدون نتيجة configure.
 * بتنضاف تلقائياً لكل ملفات C عبر -include من CMakeLists.txt. */
#ifndef AMR_TYPES_H
#define AMR_TYPES_H

typedef float ieee754_float32_t;
typedef double ieee754_float64_t;

#endif
