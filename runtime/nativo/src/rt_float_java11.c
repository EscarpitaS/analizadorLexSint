/*
 * Copyright (c) 1996, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation. Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 * Port C de BinaryToASCIIBuffer (FloatingDecimal, OpenJDK jdk-11+28).
 * Modificado en octubre de 2026: big integers acotados a binary64, aritmética
 * unsigned para reproducir el overflow Java sin UB, salida byte y sin estado.
 * Licencia y procedencia: runtime/terceros/openjdk11/.
 */
#include "rt_runtime.h"
#include <assert.h>
#include <math.h>
#include <string.h>

/* 2048 bits exceden el máximo de B/S/M del algoritmo para binary64 (<1200).
 * Solo se necesita multiplicar por 5/10, desplazar, comparar y restar. */
typedef struct { unsigned n; uint32_t w[64]; } Big;
static void norm(Big *a) { while (a->n && a->w[a->n-1]==0) a->n--; }
static Big integer(uint64_t x) { Big a={0,{0}}; a.w[0]=(uint32_t)x; a.w[1]=(uint32_t)(x>>32); a.n=a.w[1]?2:x?1:0; return a; }
static void mul(Big *a, uint32_t b) {
    uint64_t carry=0;
    for(unsigned i=0;i<a->n;i++) { uint64_t v=(uint64_t)a->w[i]*b+carry; a->w[i]=(uint32_t)v; carry=v>>32; }
    if(carry) { assert(a->n<64); a->w[a->n++]=(uint32_t)carry; }
}
static void shift(Big *a, unsigned s) {
    if(!a->n || !s) return;
    Big b=integer(0); unsigned words=s/32, bits=s%32;
    assert(a->n+words+(bits!=0)<=64);
    for(unsigned i=0;i<a->n;i++) {
        uint64_t v=(uint64_t)a->w[i]<<bits;
        b.w[i+words]|=(uint32_t)v;
        if(bits) b.w[i+words+1]|=(uint32_t)(v>>32);
    }
    b.n=a->n+words+(bits!=0); norm(&b); *a=b;
}
static Big power(uint64_t x, int p5, int p2) {
    assert(p5>=0 && p2>=0); Big a=integer(x);
    while(p5--) mul(&a,5);
    shift(&a,(unsigned)p2); return a;
}
static int cmp(const Big *a, const Big *b) {
    if(a->n!=b->n) return a->n>b->n?1:-1;
    for(unsigned i=a->n;i>0;i--) if(a->w[i-1]!=b->w[i-1]) return a->w[i-1]>b->w[i-1]?1:-1;
    return 0;
}
static void sub(Big *a, const Big *b) {
    uint64_t borrow=0;
    for(unsigned i=0;i<a->n;i++) {
        uint64_t x=a->w[i], y=(i<b->n?b->w[i]:0)+borrow;
        a->w[i]=(uint32_t)(x-y); borrow=x<y;
    }
    assert(!borrow); norm(a);
}
static Big sum(const Big *a, const Big *b) {
    Big r=integer(0); r.n=a->n>b->n?a->n:b->n; uint64_t carry=0;
    for(unsigned i=0;i<r.n;i++) {
        uint64_t x=(uint64_t)(i<a->n?a->w[i]:0)+(i<b->n?b->w[i]:0)+carry;
        r.w[i]=(uint32_t)x; carry=x>>32;
    }
    if(carry) { assert(r.n<64); r.w[r.n++]=(uint32_t)carry; } return r;
}
static unsigned quotient(Big *b, const Big *s) {
    unsigned q=0; while(cmp(b,s)>=0) { sub(b,s); q++; }
    assert(q<10); mul(b,10); return q;
}
static uint64_t pow5(unsigned p) { uint64_t v=1; while(p--) v*=5; return v; }
static int max(int a,int b) { return a>b?a:b; }
static const int n5[]={0,3,5,7,10,12,14,17,19,21,24,26,28,31,33,35,38,40,42,45,47,49,52,54,56,59,61};
static int bits5(int p) { return p<27?n5[p]:p*3; }
static int negative(uint64_t x,unsigned bits) { return (int)((x>>(bits-1))&1); }
static int signed_less(uint64_t a,uint64_t b,unsigned bits) { return negative(a,bits)!=negative(b,bits)?negative(a,bits):a<b; }
static void roundup(char *digits,int n,int *exp) {
    int i=n-1; while(digits[i]=='9' && i>0) digits[i--]='0';
    if(digits[i]=='9') { digits[0]='1'; (*exp)++; } else digits[i]++;
}
static void digits_of(uint64_t fract,int binexp,int significant,char *digits,int *nd,int *decimal) {
    int tail=__builtin_ctzll(fract), nfract=53-tail;
    int tiny=max(0,nfract-binexp-1);
    if(binexp>=-21 && binexp<=62 && tiny==0) {
        unsigned p=(unsigned)(binexp-significant-1); int insignificant=0;
        if(binexp>significant && p>1 && p<64) { uint64_t v=UINT64_C(1)<<p; while(v>=10) { insignificant++; v/=10; } }
        uint64_t value=binexp>=52?fract<<(binexp-52):fract>>(52-binexp);
        uint64_t scale=1; for(int i=0;i<insignificant;i++) scale*=10;
        if(insignificant) { uint64_t rem=value%scale; value/=scale; if(rem>=scale/2) value++; }
        char reversed[20]; int n=0, exp=insignificant;
        while(value%10==0) { exp++; value/=10; }
        do { reversed[n++]=(char)('0'+value%10); value/=10; } while(value);
        for(int i=0;i<n;i++) digits[i]=reversed[n-i-1];
        *nd=n; *decimal=exp+n; return;
    }
    uint64_t scaled=UINT64_C(0x3ff0000000000000)|(fract&UINT64_C(0xfffffffffffff));
    double d2; memcpy(&d2,&scaled,8);
    /* -ffp-contract=off conserva las operaciones y redondeos separados de Java. */
    double estimate=(d2-1.5)*0.289529654+0.176091259+(double)binexp*0.301029995663981;
    int dec=(int)floor(estimate);
    int b5=max(0,-dec), b2=b5+tiny+binexp;
    int s5=max(0,dec), s2=s5+tiny, m5=b5, m2=b2-significant;
    fract>>=tail; b2-=nfract-1;
    int common=b2<s2?b2:s2; b2-=common; s2-=common; m2-=common;
    if(nfract==1) m2--;
    if(m2<0) { b2-=m2; s2-=m2; m2=0; }
    int bbits=nfract+b2+bits5(b5), sbits=s2+1+bits5(s5+1);
    int n=0,low,high,difference=0;
    if(bbits<64 && sbits<64) {
        unsigned bits=bbits<32 && sbits<32?32:64;
        uint64_t mask=bits==32?UINT32_MAX:UINT64_MAX;
        uint64_t b=(fract*pow5((unsigned)b5)<<b2)&mask, s=pow5((unsigned)s5)<<s2;
        uint64_t m=pow5((unsigned)m5)<<m2, tens=s*10;
        unsigned q=(unsigned)(b/s); b=10*(b%s); m=(m*10)&mask;
        low=signed_less(b,m,bits); high=signed_less(tens,(b+m)&mask,bits);
        if(q==0 && !high) dec--; else digits[n++]=(char)('0'+q);
        if(dec<-3 || dec>=8) low=high=0;
        while(!low && !high) {
            q=(unsigned)(b/s); b=10*(b%s); m=(m*10)&mask;
            if(m && !negative(m,bits)) { low=b<m; high=signed_less(tens,(b+m)&mask,bits); }
            else low=high=1;
            assert(n<20); digits[n++]=(char)('0'+q);
        }
        uint64_t diff=(b*2-tens)&mask; difference=diff==0?0:negative(diff,bits)?-1:1;
    } else {
        Big s=power(1,s5,s2), b=power(fract,b5,b2), m=power(1,m5+1,m2+1), tens=power(1,s5+1,s2+1);
        unsigned q=quotient(&b,&s); Big added=sum(&b,&m);
        low=cmp(&b,&m)<0; high=cmp(&tens,&added)<=0;
        if(q==0 && !high) dec--; else digits[n++]=(char)('0'+q);
        if(dec<-3 || dec>=8) low=high=0;
        while(!low && !high) {
            q=quotient(&b,&s); mul(&m,10); added=sum(&b,&m);
            low=cmp(&b,&m)<0; high=cmp(&tens,&added)<=0;
            assert(n<20); digits[n++]=(char)('0'+q);
        }
        if(low && high) { shift(&b,1); difference=cmp(&b,&tens); }
    }
    *decimal=dec+1; *nd=n;
    if(high && (!low || difference>0 || (difference==0 && (digits[n-1]&1)))) roundup(digits,n,decimal);
}
size_t rt_double_to_java11(double value,char out[32]) {
    uint64_t bits; memcpy(&bits,&value,8);
    uint64_t fract=bits&UINT64_C(0xfffffffffffff); int exp=(int)((bits>>52)&2047), neg=(int)(bits>>63);
    if(exp==2047) { const char *s=fract?"NaN":neg?"-Infinity":"Infinity"; strcpy(out,s); return strlen(s); }
    if(exp==0 && fract==0) { const char *s=neg?"-0.0":"0.0"; strcpy(out,s); return strlen(s); }
    int significant;
    if(exp==0) { int leading=__builtin_clzll(fract), shift=leading-11; fract<<=shift; exp=1-shift; significant=64-leading; }
    else { fract|=UINT64_C(1)<<52; significant=53; }
    exp-=1023; char digits[20]; int n,dec; digits_of(fract,exp,significant,digits,&n,&dec);
    size_t i=0; if(neg) out[i++]='-';
    if(dec>0 && dec<8) {
        int take=n<dec?n:dec; memcpy(out+i,digits,(size_t)take); i+=(size_t)take;
        if(take<dec) { while(take++<dec) out[i++]='0'; out[i++]='.'; out[i++]='0'; }
        else { out[i++]='.'; if(take<n) { memcpy(out+i,digits+take,(size_t)(n-take)); i+=(size_t)(n-take); } else out[i++]='0'; }
    } else if(dec<=0 && dec>-3) {
        out[i++]='0'; out[i++]='.'; for(int j=0;j<-dec;j++) out[i++]='0'; memcpy(out+i,digits,(size_t)n); i+=(size_t)n;
    } else {
        out[i++]=digits[0]; out[i++]='.';
        if(n>1) { memcpy(out+i,digits+1,(size_t)(n-1)); i+=(size_t)(n-1); } else out[i++]='0';
        out[i++]='E'; int e=dec<=0?1-dec:dec-1; if(dec<=0) out[i++]='-';
        if(e>=100) out[i++]=(char)('0'+e/100);
        if(e>=10) out[i++]=(char)('0'+e/10%10);
        out[i++]=(char)('0'+e%10);
    }
    assert(i<32); out[i]=0; return i;
}
