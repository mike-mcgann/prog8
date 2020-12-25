%target cx16
%import gfx2
%import textio
%import diskio
%option no_sysinit

; CommanderX16 Image file format.  (EXPERIMENTAL/ UNFINISHED)
; Numbers are encoded in little endian format (lsb first).
;
; offset      value
; -----------------
; HEADER (12 bytes):
; 0-1    'CI' in petscii , from "CommanderX16 Image".
; 2      Size of the header data following this byte (always 9, could become more if format changes)
; 3-4    Width in pixels  (must be multiple of 8)
; 5-6    Height in pixels
; 7      Bits-per-pixel  (1, 2, 4 or 8)  (= 2, 4, 16 or 256 colors)
;          this also determines the number of palette entries following later.
; 8      Settings bits.
;          bit 0 and 1 = compression.  00 = uncompressed
;                                      01 = RLE        [TODO not yet implemented]
;                                      10 = LZSA       [TODO not yet implemented]
;                                      11 = Exomizer   [TODO not yet implemented]
;          bit 2 = palette format.  0 = 4 bits/channel  (2 bytes per color, $0R $GB)
;                                   1 = 8 bits/channel  (3 bytes per color, $RR $GG $BB)
;                  4 bits per channel is what the Vera in the Cx16 supports.
;          bit 3 = bitmap format.   0 = raw bitmap pixels
;                                   1 = tile-based image   [TODO not yet implemented]
;          bit 4 = hscale (horizontal display resulution) 0 = 320 pixels, 1 = 640 pixels
;          bit 5 = vscale (vertical display resulution) 0 = 240 pixels, 1 = 480 pixels
;          bit 6,7: reserved, set to 0
; 9-11   Size of the bitmap data following the palette data.
;          This is a 24-bits number, can be 0 ("unknown", in which case just read until the end).
;
; PALETTE (always present but size varies):
; 12-... Color palette. Number of entries = 2 ^ bits-per-pixel.  Number of bytes per
;          entry is 2 or 3, depending on the chosen palette format in the setting bits.
;
; BITMAPDATA (size varies):
; After this, the actual image data follows.
; If the bitmap format is 'raw bitmap pixels', the bimap is simply written as a sequence
; of bytes making up the image's scan lines. #bytes per scan line = width * bits-per-pixel / 8
; If it is 'tiles', .... [TODO]
; If a compression scheme is used, the bitmap data here has to be decompressed first.
; TODO: with compressed files, store the data in compressed chunks of max 8kb uncompressed?
; (it is a problem to load let alone decompress a full bitmap at once because there will likely not be enough ram to do that)
; (doing it in chunks of 8 kb allows for sticking each chunk in one of the banked 8kb ram blocks, or even copy it directly to the screen)

ci_module {
    %option force_output
    ubyte[256] buffer
    ubyte[256] buffer2  ; add two more buffers to make enough space
    ubyte[256] buffer3  ;   to store a 256 color palette
    ubyte[256] buffer4  ;  .. and some more to be able to store 1280=
    ubyte[256] buffer5  ;     two 640 bytes worth of bitmap scanline data

    sub show_image(uword filename) -> ubyte {
        ubyte read_success = false
        uword bitmap_load_address = progend()
        ; uword max_bitmap_size = $9eff - bitmap_load_address

        if(diskio.f_open(8, filename)) {
            uword size = diskio.f_read(buffer, 12)  ; read the header
            if size==12 {
                if buffer[0]=='c' and buffer[1]=='i' and buffer[2] == 9 {
                    uword width = mkword(buffer[4], buffer[3])
                    uword height = mkword(buffer[6], buffer[5])
                    ubyte bpp = buffer[7]
                    uword num_colors = 2 ** bpp
                    ubyte flags = buffer[8]
                    ubyte compression = flags & %00000011
                    ubyte palette_format = (flags & %00000100) >> 2
                    ubyte bitmap_format = (flags & %00001000) >> 3
                    ; ubyte hscale = (flags & %00010000) >> 4
                    ; ubyte vscale = (flags & %00100000) >> 5
                    uword bitmap_size = mkword(buffer[10], buffer[9])
                    uword palette_size = num_colors*2
                    if palette_format
                        palette_size += num_colors  ; 3
                    if width > gfx2.width {
                        txt.print("image is too wide for the display!\n")
                    } else if compression!=0 {
                        txt.print("compressed image not yet supported!\n")    ; TODO implement the various decompressions
                    } else if bitmap_format==1 {
                        txt.print("tiled bitmap not yet supported!\n")       ; TODO implement tiled image
                    } else {
                        size = diskio.f_read(buffer, palette_size)
                        if size==palette_size {
                            if compression {
                                txt.print("todo: compressed image support\n")
                            } else {
                                ; uncompressed bitmap data. read it a scanline at a time and display as we go.
                                ; restrict height to the maximun that can be displayed
                                if height > gfx2.height
                                    height = gfx2.height
                                if palette_format
                                    palette.set_rgb8(buffer, num_colors)
                                else
                                    palette.set_rgb4(buffer, num_colors)
                                gfx2.clear_screen()
                                gfx2.position(0 ,0)
                                uword scanline_size = width * bpp / 8
                                ubyte y
                                for y in 0 to lsb(height)-1 {
                                    void diskio.f_read(buffer, scanline_size)
                                    when bpp {
                                        8 -> gfx2.next_pixels(buffer, scanline_size)
                                        4 -> display_scanline_16c(buffer, scanline_size)
                                        2 -> display_scanline_4c(buffer, scanline_size)
                                        1 -> display_scanline_2c(buffer, scanline_size)
                                    }
                                }
                                read_success = true
                            }
                        }
                    }
                }
            }

            diskio.f_close()
        }

        return read_success
    }

    sub display_scanline_16c(uword dataptr, uword numbytes) {
        ; TODO
    }

    sub display_scanline_4c(uword dataptr, uword numbytes) {
        ; TODO
    }

    sub display_scanline_2c(uword dataptr, uword numbytes) {
        ; TODO
    }
}
