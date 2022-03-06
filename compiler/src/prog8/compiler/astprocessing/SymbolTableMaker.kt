package prog8.compiler.astprocessing

import prog8.ast.Program
import prog8.ast.base.FatalAstException
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.ast.walk.IAstVisitor
import prog8.compilerinterface.*
import java.util.*

internal class SymbolTableMaker: IAstVisitor {

    private val st = SymbolTable()
    private val scopestack = Stack<StNode>()

    fun makeFrom(program: Program): SymbolTable {
        scopestack.clear()
        st.children.clear()
        this.visit(program)
        program.builtinFunctions.names.forEach {
            val node = StNode(it, StNodeType.BUILTINFUNC, Position.DUMMY)
            st.add(node)
        }
        return st
    }

    override fun visit(block: Block) {
        val node = StNode(block.name, StNodeType.BLOCK, block.position)
        st.add(node)
        scopestack.push(node)
        super.visit(block)
        scopestack.pop()
        // st.origAstLinks[block] = node
    }

    override fun visit(subroutine: Subroutine) {
        val node = StNode(subroutine.name, StNodeType.SUBROUTINE, subroutine.position)
        scopestack.peek().add(node)
        scopestack.push(node)
        super.visit(subroutine)
        scopestack.pop()
        // st.origAstLinks[subroutine] = node
    }

    override fun visit(decl: VarDecl) {
        val node =
            when(decl.type) {
                VarDeclType.VAR -> {
                    val initialNumeric = (decl.value as? NumericLiteral)?.number
                    val initialStringLit = decl.value as? StringLiteral
                    val initialString = if(initialStringLit==null) null else Pair(initialStringLit.value, initialStringLit.encoding)
                    val initialArrayLit = decl.value as? ArrayLiteral
                    val initialArray = makeInitialArray(initialArrayLit)
                    StStaticVariable(decl.name, decl.datatype, initialNumeric, initialString, initialArray, decl.arraysize?.constIndex(), map(decl.zeropage), decl.position)
                }
                VarDeclType.CONST -> StConstant(decl.name, decl.datatype, (decl.value as NumericLiteral).number, decl.position)
                VarDeclType.MEMORY -> StMemVar(decl.name, decl.datatype, (decl.value as NumericLiteral).number.toUInt(), decl.position)
            }
        scopestack.peek().add(node)
        // st.origAstLinks[decl] = node
    }

    private fun map(zpw: ZeropageWish): StZeropageWish = when(zpw) {
        ZeropageWish.REQUIRE_ZEROPAGE -> StZeropageWish.REQUIRE_ZEROPAGE
        ZeropageWish.PREFER_ZEROPAGE -> StZeropageWish.PREFER_ZEROPAGE
        ZeropageWish.DONTCARE -> StZeropageWish.DONTCARE
        ZeropageWish.NOT_IN_ZEROPAGE -> StZeropageWish.NOT_IN_ZEROPAGE
    }

    private fun makeInitialArray(arrayLit: ArrayLiteral?): StArray? {
        if(arrayLit==null)
            return null
        return arrayLit.value.map {
            when(it){
                is AddressOf -> StArrayElement(null, it.identifier.nameInSource)
                is IdentifierReference -> StArrayElement(null, it.nameInSource)
                is NumericLiteral -> StArrayElement(it.number, null)
                else -> throw FatalAstException("weird element dt in array literal")
            }
        }.toList()
    }

    override fun visit(label: Label) {
        val node = StNode(label.name, StNodeType.LABEL, label.position)
        scopestack.peek().add(node)
        // st.origAstLinks[label] = node
    }
}
