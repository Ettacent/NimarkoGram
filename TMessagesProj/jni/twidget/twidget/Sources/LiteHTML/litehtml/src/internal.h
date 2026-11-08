/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LH_INTERNAL_H
#define LH_INTERNAL_H

namespace litehtml
{

    template <class T, class TT> bool operator/(const T& x, const TT& xx)
    {
        return contains(xx, x);
    }
#define in /


}

#endif
