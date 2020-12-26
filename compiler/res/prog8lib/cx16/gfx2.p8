%target cx16

; Bitmap pixel graphics module for the CommanderX16
; Custom routines to use the full-screen 640x480 and 320x240 screen modes.
; This is only compatible with the Cx16.
; For compatible graphics code that words on C64 too, use the "graphics" module instead.


; TODO this is in development.  Add line drawing, circles and discs (like the graphics module has)
; TODO enable text layer too?

gfx2 {

    ; read-only control variables:
    ubyte active_mode = 255
    uword width = 0
    uword height = 0
    ubyte bpp = 0

    const ubyte charset_orig_bank = $0
    const uword charset_orig_addr = $f800        ; in bank 0, so $0f800
    const ubyte charset_bank = $1
    const uword charset_addr = $f000       ; in bank 1, so $1f000

    sub screen_mode(ubyte mode) {
        ; mode 0 = bitmap 320 x 240 x 1c monochrome
        ; mode 1 = bitmap 320 x 240 x 256c
        ; mode 128 = bitmap 640 x 480 x 1c monochrome
        ; ...other modes?

        ; copy the lower-case charset to the upper part of the vram, so we can use it later to plot text
        cx16.screen_set_charset(3, 0)
        cx16.vaddr(charset_orig_bank, charset_orig_addr, 0, 1)
        cx16.vaddr(charset_bank, charset_addr, 1, 1)
        repeat 256*8 {
            cx16.VERA_DATA1 = cx16.VERA_DATA0
        }

        when mode {
            0 -> {
                ; 320 x 240 x 1c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 64
                cx16.VERA_DC_VSCALE = 64
                cx16.VERA_L1_CONFIG = %00000100
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = 0
                width = 320
                height = 240
                bpp = 1
            }
            1 -> {
                ; 320 x 240 x 256c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 64
                cx16.VERA_DC_VSCALE = 64
                cx16.VERA_L1_CONFIG = %00000111
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = 0
                width = 320
                height = 240
                bpp = 8
            }
            128 -> {
                ; 640 x 480 x 1c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 128
                cx16.VERA_DC_VSCALE = 128
                cx16.VERA_L1_CONFIG = %00000100
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = %00000001
                width = 640
                height = 480
                bpp = 1
            }
            255 -> {
                ; back to default text mode and colors
                cx16.VERA_CTRL = %10000000      ; reset VERA and palette
                c64.CINT()      ; back to text mode
                width = 0
                height = 0
                bpp = 0
            }
        }

        active_mode = mode
        if bpp
            clear_screen()
    }

    sub clear_screen() {
        position(0, 0)
        when active_mode {
            0 -> {
                ; 320 x 240 x 1c
                repeat 240/2/8
                    cs_innerloop640()
            }
            1 -> {
                ; 320 x 240 x 256c
                repeat 240/2
                    cs_innerloop640()
            }
            128 -> {
                ; 640 x 480 x 1c
                repeat 480/8
                    cs_innerloop640()
            }
        }
        position(0, 0)
    }

    sub plot(uword x, uword y, ubyte color) {
        ubyte[8] bits = [128, 64, 32, 16, 8, 4, 2, 1]
        uword addr
        ubyte value
        when active_mode {
            0 -> {
                addr = x/8 + y*(320/8)
                value = bits[lsb(x)&7]
                cx16.vpoke_or(0, addr, value)
            }
            128 -> {
                addr = x/8 + y*(640/8)
                value = bits[lsb(x)&7]
                cx16.vpoke_or(0, addr, value)
            }
            1 -> {
                void addr_mul_320_add_24(y, x)      ; 24 bits result is in r0 and r1L
                ubyte bank = lsb(cx16.r1)
                cx16.vpoke(bank, cx16.r0, color)
            }
        }
        ; activate vera auto-increment mode so next_pixel() can be used after this
        cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111) | %00010000
        return
    }

    sub position(uword x, uword y) {
        when active_mode {
            0 -> {
                cx16.r0 = y*(320/8) + x/8
                cx16.vaddr(0, cx16.r0, 0, 1)
            }
            128 -> {
                cx16.r0 = y*(640/8) + x/8
                cx16.vaddr(0, cx16.r0, 0, 1)
            }
            1 -> {
                void addr_mul_320_add_24(y, x)      ; 24 bits result is in r0 and r1L
                ubyte bank = lsb(cx16.r1)
                cx16.vaddr(bank, cx16.r0, 0, 1)
            }
        }
    }

    asmsub next_pixel(ubyte color @A) {
        ; -- sets the next pixel byte to the graphics chip.
        ;    for 8 bpp screens this will plot 1 pixel. for 1 bpp screens it will actually plot 8 pixels at once (bitmask).
        %asm {{
            sta  cx16.VERA_DATA0
            rts
        }}
    }

    sub next_pixels(uword pixels, uword amount) {
        repeat msb(amount) {
            repeat 256 {
                cx16.VERA_DATA0 = @(pixels)
                pixels++
            }
        }
        repeat lsb(amount) {
            cx16.VERA_DATA0 = @(pixels)
            pixels++
        }
    }

    asmsub set_8_pixels_from_bits(ubyte bits @R0, ubyte oncolor @A, ubyte offcolor @Y) {
        %asm {{
            phx
            ldx  #8
-           asl  cx16.r0
            bcc  +
            sta  cx16.VERA_DATA0
            bra  ++
+           sty  cx16.VERA_DATA0
+           dex
            bne  -
            plx
            rts
        }}
    }

    sub text(uword x, uword y, ubyte color, uword sctextptr) {
        ; -- Write some text at the given pixel position.
        ;    The text string must be in screencode encoding (not petscii!).
        ;    NOTE: in monochrome (1bpp) screen modes, x position is currently constrained to mulitples of 8 !
        uword chardataptr
        ubyte cy
        ubyte cb
        when active_mode {
            0, 128 -> {
                ; 1-bitplane modes
                cy = 11<<4     ; auto increment 40
                if active_mode>=128
                    cy = 12<<4    ; auto increment 80
                while @(sctextptr) {
                    chardataptr = charset_addr + (@(sctextptr) as uword)*8
                    cx16.vaddr(charset_bank, chardataptr, 1, 1)
                    position(x,y)
                    cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111) | cy      ; enable auto increment 40 or 80
                    repeat 8 {
                        cx16.VERA_DATA0 = cx16.VERA_DATA1
                        x++
                    }
                    sctextptr++
                }
            }
            1 -> {
                ; 320 x 240 x 256c
                while @(sctextptr) {
                    chardataptr = charset_addr + (@(sctextptr) as uword)*8
                    cx16.vaddr(charset_bank, chardataptr, 1, 1)
                    repeat 8 {
                        position(x,y)
                        y++
                        %asm {{
                            lda  cx16.VERA_DATA1
                            sta  P8ZP_SCRATCH_B1
                            ldy  #8
-                           lda  #0
                            asl  P8ZP_SCRATCH_B1
                            adc  #0
                            sta  cx16.VERA_DATA0
                            dey
                            bne  -
                        }}
                    }
                    x+=8
                    y-=8
                    sctextptr++
                }
            }
        }
    }

    asmsub cs_innerloop640() {
        %asm {{
            ldy  #80
-           stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            dey
            bne  -
            rts
        }}
    }

    asmsub addr_mul_320_add_24(uword address @R0, uword value @AY) -> uword @R0, ubyte @R1  {
            %asm {{
                sta  P8ZP_SCRATCH_W1
                sty  P8ZP_SCRATCH_W1+1
                lda  cx16.r0
                sta  P8ZP_SCRATCH_B1
                lda  cx16.r0+1
                sta  cx16.r1
                sta  P8ZP_SCRATCH_REG
                lda  cx16.r0
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                sta  cx16.r0
                lda  P8ZP_SCRATCH_B1
                clc
                adc  P8ZP_SCRATCH_REG
                sta  cx16.r0+1
                bcc  +
                inc  cx16.r1
+		        ; now add the value to this 24-bits number
                lda  cx16.r0
                clc
                adc  P8ZP_SCRATCH_W1
                sta  cx16.r0
                lda  cx16.r0+1
                adc  P8ZP_SCRATCH_W1+1
                sta  cx16.r0+1
                bcc  +
                inc  cx16.r1
+               lda  cx16.r1
                rts
            }}
        }

}
