// Force-included with -include into every translation unit of the Android build.
//
// libc++ 19 and later (NDK r27 and later) removed the non-standard primary
// template of std::char_traits. std::basic_string<uint8_t>, which mqtt/MQTT.h
// uses as a byte buffer, then fails to instantiate. libstdc++ on Linux still
// accepts it. This header supplies an explicit char_traits<unsigned char>
// specialization with the same semantics the Linux build gets implicitly.
#pragma once
// The -include flag reaches C files as well (windows/libpinedio_ch341dll.c);
// the shim is C++ only.
#if defined(__ANDROID__) && defined(__cplusplus)
#include <cstring>
#include <cstdint>
#include <cstdio>   // EOF
#include <cwchar>   // mbstate_t
#include <iosfwd>   // streamoff, streampos, and the char_traits forward declaration

namespace std {
template <> struct char_traits<unsigned char> {
    using char_type = unsigned char;
    using int_type = int;
    using off_type = std::streamoff;
    using pos_type = std::streampos;
    using state_type = std::mbstate_t;

    static constexpr void assign(char_type &a, const char_type &b) noexcept { a = b; }
    static constexpr bool eq(char_type a, char_type b) noexcept { return a == b; }
    static constexpr bool lt(char_type a, char_type b) noexcept { return a < b; }

    static int compare(const char_type *a, const char_type *b, size_t n) { return n ? std::memcmp(a, b, n) : 0; }
    static size_t length(const char_type *s) { return std::strlen(reinterpret_cast<const char *>(s)); }
    static const char_type *find(const char_type *s, size_t n, const char_type &c)
    {
        return static_cast<const char_type *>(std::memchr(s, c, n));
    }
    static char_type *move(char_type *d, const char_type *s, size_t n)
    {
        return n ? static_cast<char_type *>(std::memmove(d, s, n)) : d;
    }
    static char_type *copy(char_type *d, const char_type *s, size_t n)
    {
        return n ? static_cast<char_type *>(std::memcpy(d, s, n)) : d;
    }
    static char_type *assign(char_type *s, size_t n, char_type c)
    {
        return n ? static_cast<char_type *>(std::memset(s, c, n)) : s;
    }

    static constexpr int_type not_eof(int_type c) noexcept { return eq_int_type(c, eof()) ? 0 : c; }
    static constexpr char_type to_char_type(int_type c) noexcept { return static_cast<char_type>(c); }
    static constexpr int_type to_int_type(char_type c) noexcept { return static_cast<int_type>(c); }
    static constexpr bool eq_int_type(int_type a, int_type b) noexcept { return a == b; }
    static constexpr int_type eof() noexcept { return EOF; }
};
} // namespace std
#endif
