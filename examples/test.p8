%import textio
%import floats
%zeropage basicsafe

; TODO fix VM : produces wrong number of primes (and varies too, so it uses uninitialized memory somewhere)


main {
    const uword SIZE = 16000

    uword @zp flags_ptr = memory("flags", SIZE/8+1, $100)
    ubyte[] bitv = [ $01, $02, $04, $08, $10, $20, $40, $80 ]

    sub start() {
        txt.print("calculating... (expecting 3431): ")
        txt.print_uw(sieve())
        txt.print(" primes\n")
    }

    sub check_flag(uword idx) -> ubyte
    {
        ubyte mask = bitv[lsb(idx)&7]
        ubyte flag = flags_ptr[idx/8]
        return flag & mask
    }

    sub clear_flag(uword idx)
    {
        ubyte mask = bitv[lsb(idx)&7]
        ubyte flag = flags_ptr[idx/8]
        flag &= ~mask
        flags_ptr[idx/8] = flag
    }

    sub sieve() -> uword {
        uword prime
        uword k
        uword count=0
        uword i
        sys.memset(flags_ptr, SIZE/8+1, $ff)

        for i in 0 to SIZE-1 {
            if check_flag(i) {
                prime = i*2 + 3
                k = i + prime
                while k < SIZE {
                    clear_flag(k)
                    k += prime
                }
                count++
            }
        }
        return count
    }
}
