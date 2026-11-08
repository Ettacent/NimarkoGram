/* Modifications Copyright (C) 2026 Ettacent */

/*
 * strtod.c --
 *
 *	Source code for the "strtod" library procedure.
 *
 * Copyright (c) 1988-1993 The Regents of the University of California.
 * Copyright (c) 1994 Sun Microsystems, Inc.
 *
 * Permission to use, copy, modify, and distribute this
 * software and its documentation for any purpose and without
 * fee is hereby granted, provided that the above copyright
 * notice appear in all copies.  The University of California
 * makes no representations about the suitability of this
 * software for any purpose.  It is provided "as is" without
 * express or implied warranty.
 *
 * RCS: @(#) $Id$
 */

#include "html.h"
#include <cstdlib>
#include <cctype>
#include <cerrno>

#ifndef TRUE
#define TRUE  1
#define FALSE 0
#endif
#ifndef NULL
#define NULL 0
#endif

static int maxExponent = 511;




static double powersOf10[] = {
                              10.,
                              100.,
                              1.0e4, 1.0e8, 1.0e16, 1.0e32, 1.0e64, 1.0e128, 1.0e256};


double litehtml::t_strtod(const char* string, char** endPtr)
{
    int         sign, expSign = FALSE;
    double      fraction, dblExp, *d;
    const char* p;
    int         c;
    int         exp     = 0;
    int         fracExp = 0;








    int mantSize;
    int decPt;

    const char* pExp;



    p = string;
    while(isspace(*p))
    {
        p += 1;
    }
    if(*p == '-')
    {
        sign  = TRUE;
        p    += 1;
    } else
    {
        if(*p == '+')
        {
            p += 1;
        }
        sign = FALSE;
    }


    decPt = -1;
    for(mantSize = 0;; mantSize += 1)
    {
        c = *p;
        if(!t_isdigit(c))
        {
            if((c != '.') || (decPt >= 0))
            {
                break;
            }
            decPt = mantSize;
        }
        p += 1;
    }


    pExp  = p;
    p    -= mantSize;
    if(decPt < 0)
    {
        decPt = mantSize;
    } else
    {
        mantSize -= 1;
    }
    if(mantSize > 18)
    {
        fracExp  = decPt - 18;
        mantSize = 18;
    } else
    {
        fracExp = decPt - mantSize;
    }
    if(mantSize == 0)
    {
        fraction = 0.0;
        p        = string;
        goto done;
    } else
    {
        int frac1, frac2;
        frac1 = 0;
        for(; mantSize > 9; mantSize -= 1)
        {
            c  = *p;
            p += 1;
            if(c == '.')
            {
                c  = *p;
                p += 1;
            }
            frac1 = 10 * frac1 + (c - '0');
        }
        frac2 = 0;
        for(; mantSize > 0; mantSize -= 1)
        {
            c  = *p;
            p += 1;
            if(c == '.')
            {
                c  = *p;
                p += 1;
            }
            frac2 = 10 * frac2 + (c - '0');
        }
        fraction = (1.0e9 * frac1) + frac2;
    }


    p = pExp;
    if((*p == 'E') || (*p == 'e'))
    {
        p += 1;
        if(*p == '-')
        {
            expSign  = TRUE;
            p       += 1;
        } else
        {
            if(*p == '+')
            {
                p += 1;
            }
            expSign = FALSE;
        }
        while(isdigit(*p))
        {
            exp  = exp * 10 + (*p - '0');
            p   += 1;
        }
    }
    if(expSign)
    {
        exp = fracExp - exp;
    } else
    {
        exp = fracExp + exp;
    }


    if(exp < 0)
    {
        expSign = TRUE;
        exp     = -exp;
    } else
    {
        expSign = FALSE;
    }
    if(exp > maxExponent)
    {
        exp   = maxExponent;
        errno = ERANGE;
    }
    dblExp = 1.0;
    for(d = powersOf10; exp != 0; exp >>= 1, d += 1)
    {
        if(exp & 01)
        {
            dblExp *= *d;
        }
    }
    if(expSign)
    {
        fraction /= dblExp;
    } else
    {
        fraction *= dblExp;
    }

done:
    if(endPtr != nullptr)
    {
        *endPtr = const_cast<char*>(p);
    }

    if(sign)
    {
        return -fraction;
    }
    return fraction;
}
