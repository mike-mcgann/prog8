package prog8.codegen.intermediate

import prog8.code.core.AssemblyError
import prog8.intermediate.SyscallRegisterBase

internal class RegisterPool {
    // reserve 0,1,2 for return values of subroutine calls and syscalls
    // TODO set this back to 0 once 'resultRegister' has been removed everywhere and DIVMOD fixed to not use r0?
    private var firstFree: Int=3
    private var firstFreeFloat: Int=3

    fun peekNext() = firstFree
    fun peekNextFloat() = firstFreeFloat

    fun nextFree(): Int {
        val result = firstFree
        firstFree++
        if(firstFree >= SyscallRegisterBase)
            throw AssemblyError("out of virtual registers (int)")
        return result
    }

    fun nextFreeFloat(): Int {
        val result = firstFreeFloat
        firstFreeFloat++
        if(firstFreeFloat >= SyscallRegisterBase)
            throw AssemblyError("out of virtual registers (fp)")
        return result
    }
}
