/* Manual gphoto2-endian.h for Android */
#ifndef GPHOTO2_ENDIAN_H
#define GPHOTO2_ENDIAN_H

#include <endian.h>
#include <stdint.h>

#define be16atoh(x) be16toh(*(uint16_t*)(x))
#define le16atoh(x) le16toh(*(uint16_t*)(x))
#define be32atoh(x) be32toh(*(uint32_t*)(x))
#define le32atoh(x) le32toh(*(uint32_t*)(x))
#define be64atoh(x) be64toh(*(uint64_t*)(x))
#define le64atoh(x) le64toh(*(uint64_t*)(x))

#define htobe16a(a,x) *(uint16_t*)(a) = htobe16(x)
#define htole16a(a,x) *(uint16_t*)(a) = htole16(x)
#define htobe32a(a,x) *(uint32_t*)(a) = htobe32(x)
#define htole32a(a,x) *(uint32_t*)(a) = htole32(x)
#define htobe64a(a,x) *(uint64_t*)(a) = htobe64(x)
#define htole64a(a,x) *(uint64_t*)(a) = htole64(x)

#endif
