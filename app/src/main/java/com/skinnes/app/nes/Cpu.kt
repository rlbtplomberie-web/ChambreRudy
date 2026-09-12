package com.skinnes.app.nes

/**
 * 6502 de la NES, sans mode decimal.
 *
 * Porte depuis une implementation Python validee par 85 verifications.
 * La table d'opcodes ci-dessous est GENEREE, pas recopiee : c'est la seule
 * facon d'etre sur qu'aucun des 231 opcodes n'a ete mal transcrit.
 */

const val F_C = 1
const val F_Z = 2
const val F_I = 4
const val F_D = 8
const val F_B = 16
const val F_U = 32
const val F_V = 64
const val F_N = 128

interface BusCpu {
    fun lire(a: Int): Int
    fun ecrire(a: Int, v: Int)
}

// Genere depuis cpu_table.py, elle-meme verifiee par 85 tests.
// Ne pas editer a la main : regenerer.

// mnemoniques
const val M_ADC = 0
const val M_AND = 1
const val M_ASL = 2
const val M_BCC = 3
const val M_BCS = 4
const val M_BEQ = 5
const val M_BIT = 6
const val M_BMI = 7
const val M_BNE = 8
const val M_BPL = 9
const val M_BRK = 10
const val M_BVC = 11
const val M_BVS = 12
const val M_CLC = 13
const val M_CLD = 14
const val M_CLI = 15
const val M_CLV = 16
const val M_CMP = 17
const val M_CPX = 18
const val M_CPY = 19
const val M_DCP = 20
const val M_DEC = 21
const val M_DEX = 22
const val M_DEY = 23
const val M_EOR = 24
const val M_INC = 25
const val M_INX = 26
const val M_INY = 27
const val M_ISB = 28
const val M_JMP = 29
const val M_JSR = 30
const val M_LAX = 31
const val M_LDA = 32
const val M_LDX = 33
const val M_LDY = 34
const val M_LSR = 35
const val M_NOP = 36
const val M_NOP_NON_DOC = 37
const val M_ORA = 38
const val M_PHA = 39
const val M_PHP = 40
const val M_PLA = 41
const val M_PLP = 42
const val M_RLA = 43
const val M_ROL = 44
const val M_ROR = 45
const val M_RRA = 46
const val M_RTI = 47
const val M_RTS = 48
const val M_SAX = 49
const val M_SBC = 50
const val M_SBC_NON_DOC = 51
const val M_SEC = 52
const val M_SED = 53
const val M_SEI = 54
const val M_SLO = 55
const val M_SRE = 56
const val M_STA = 57
const val M_STX = 58
const val M_STY = 59
const val M_TAX = 60
const val M_TAY = 61
const val M_TSX = 62
const val M_TXA = 63
const val M_TXS = 64
const val M_TYA = 65

// modes d'adressage
const val A_IMPL = 0
const val A_ACC = 1
const val A_IMM = 2
const val A_ZP = 3
const val A_ZPX = 4
const val A_ZPY = 5
const val A_ABS = 6
const val A_ABSX = 7
const val A_ABSY = 8
const val A_INDX = 9
const val A_INDY = 10
const val A_REL = 11
const val A_IND = 12

private val OP_NOM = intArrayOf(
    10, 38, 36, 55, 37, 38, 2, 55, 40, 38, 2, 36, 37, 38, 2, 55,
    9, 38, 36, 55, 37, 38, 2, 55, 13, 38, 37, 55, 37, 38, 2, 55,
    30, 1, 36, 43, 6, 1, 44, 43, 42, 1, 44, 36, 6, 1, 44, 43,
    7, 1, 36, 43, 37, 1, 44, 43, 52, 1, 37, 43, 37, 1, 44, 43,
    47, 24, 36, 56, 37, 24, 35, 56, 39, 24, 35, 36, 29, 24, 35, 56,
    11, 24, 36, 56, 37, 24, 35, 56, 15, 24, 37, 56, 37, 24, 35, 56,
    48, 0, 36, 46, 37, 0, 45, 46, 41, 0, 45, 36, 29, 0, 45, 46,
    12, 0, 36, 46, 37, 0, 45, 46, 54, 0, 37, 46, 37, 0, 45, 46,
    37, 57, 37, 49, 59, 57, 58, 49, 23, 37, 63, 36, 59, 57, 58, 49,
    3, 57, 36, 36, 59, 57, 58, 49, 65, 57, 64, 36, 36, 57, 36, 36,
    34, 32, 33, 31, 34, 32, 33, 31, 61, 32, 60, 36, 34, 32, 33, 31,
    4, 32, 36, 31, 34, 32, 33, 31, 16, 32, 62, 36, 34, 32, 33, 31,
    19, 17, 37, 20, 19, 17, 21, 20, 27, 17, 22, 36, 19, 17, 21, 20,
    8, 17, 36, 20, 37, 17, 21, 20, 14, 17, 37, 20, 37, 17, 21, 20,
    18, 50, 37, 28, 18, 50, 25, 28, 26, 50, 36, 51, 18, 50, 25, 28,
    5, 50, 36, 28, 37, 50, 25, 28, 53, 50, 37, 28, 37, 50, 25, 28
)
private val OP_MODE = intArrayOf(
    0, 9, 0, 9, 3, 3, 3, 3, 0, 2, 1, 0, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7,
    6, 9, 0, 9, 3, 3, 3, 3, 0, 2, 1, 0, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7,
    0, 9, 0, 9, 3, 3, 3, 3, 0, 2, 1, 0, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7,
    0, 9, 0, 9, 3, 3, 3, 3, 0, 2, 1, 0, 12, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7,
    2, 9, 2, 9, 3, 3, 3, 3, 0, 2, 0, 0, 6, 6, 6, 6,
    11, 10, 0, 0, 4, 4, 5, 5, 0, 8, 0, 0, 0, 7, 0, 0,
    2, 9, 2, 9, 3, 3, 3, 3, 0, 2, 0, 0, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 5, 5, 0, 8, 0, 0, 7, 7, 8, 8,
    2, 9, 2, 9, 3, 3, 3, 3, 0, 2, 0, 0, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7,
    2, 9, 2, 9, 3, 3, 3, 3, 0, 2, 0, 2, 6, 6, 6, 6,
    11, 10, 0, 10, 4, 4, 4, 4, 0, 8, 0, 8, 7, 7, 7, 7
)
private val OP_CYCLES = intArrayOf(
    7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
    2, 6, 2, 2, 4, 4, 4, 4, 2, 5, 2, 2, 2, 5, 2, 2,
    2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
    2, 5, 2, 5, 4, 4, 4, 4, 2, 4, 2, 2, 4, 4, 4, 4,
    2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
    2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7
)
private val OP_PENALITE = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0
)

class Cpu(@JvmField val bus: BusCpu) {
    @JvmField var a = 0
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var sp = 0xFD
    @JvmField var pc = 0
    @JvmField var p = 0x24
    @JvmField var cycles = 0L
    @JvmField var nmiDemande = false
    @JvmField var irqDemande = false

    private fun l8(a: Int) = bus.lire(a and 0xFFFF) and 0xFF
    private fun e8(a: Int, v: Int) = bus.ecrire(a and 0xFFFF, v and 0xFF)
    private fun l16(a: Int) = l8(a) or (l8(a + 1) shl 8)

    /** Le 6502 ne franchit pas la page sur l'indirection : bug materiel reproduit. */
    private fun l16Bug(a: Int): Int =
        if ((a and 0xFF) == 0xFF) l8(a) or (l8(a and 0xFF00) shl 8)
        else l8(a) or (l8(a + 1) shl 8)

    private fun push(v: Int) { e8(0x100 + sp, v); sp = (sp - 1) and 0xFF }
    private fun pop(): Int { sp = (sp + 1) and 0xFF; return l8(0x100 + sp) }

    private fun majZN(v: Int) {
        val w = v and 0xFF
        p = if (w == 0) p or F_Z else p and F_Z.inv()
        p = if (w and 0x80 != 0) p or F_N else p and F_N.inv()
    }

    private fun set(masque: Int, oui: Boolean) {
        p = if (oui) p or masque else p and masque.inv()
    }

    fun reset() {
        pc = l16(0xFFFC); sp = 0xFD; p = 0x24
        a = 0; x = 0; y = 0; cycles = 0L
        nmiDemande = false; irqDemande = false
    }

    private fun nmi() {
        push((pc shr 8) and 0xFF); push(pc and 0xFF)
        push((p and F_B.inv()) or F_U)
        set(F_I, true)
        pc = l16(0xFFFA)
        cycles += 7
    }

    private fun irq() {
        push((pc shr 8) and 0xFF); push(pc and 0xFF)
        push((p and F_B.inv()) or F_U)
        set(F_I, true)
        pc = l16(0xFFFE)
        cycles += 7
    }

    // ---------- operations ----------
    private fun opAdc(v: Int) {
        val s = a + v + (if (p and F_C != 0) 1 else 0)
        set(F_C, s > 0xFF)
        val r = s and 0xFF
        set(F_V, ((a xor r) and (v xor r) and 0x80) != 0)
        a = r; majZN(r)
    }

    private fun opCmp(reg: Int, v: Int) {
        set(F_C, reg >= v)
        majZN((reg - v) and 0xFF)
    }

    private fun opAsl(v: Int): Int { set(F_C, v and 0x80 != 0); val r = (v shl 1) and 0xFF; majZN(r); return r }
    private fun opLsr(v: Int): Int { set(F_C, v and 1 != 0); val r = v shr 1; majZN(r); return r }
    private fun opRol(v: Int): Int {
        val e = if (p and F_C != 0) 1 else 0
        set(F_C, v and 0x80 != 0); val r = ((v shl 1) or e) and 0xFF; majZN(r); return r
    }
    private fun opRor(v: Int): Int {
        val e = if (p and F_C != 0) 0x80 else 0
        set(F_C, v and 1 != 0); val r = (v shr 1) or e; majZN(r); return r
    }

    private var adr = 0
    private var sautPage = false

    fun ecrire(e: Etat) {
        e.put(a); e.put(x); e.put(y); e.put(sp); e.put(pc); e.put(p)
        e.put(cycles); e.put(nmiDemande); e.put(irqDemande); e.put(adr); e.put(sautPage)
    }

    fun lire(e: Etat) {
        a = e.i(); x = e.i(); y = e.i(); sp = e.i(); pc = e.i(); p = e.i()
        cycles = e.l(); nmiDemande = e.b(); irqDemande = e.b(); adr = e.i(); sautPage = e.b()
    }

    private fun adresser(mode: Int) {
        sautPage = false
        when (mode) {
            A_IMM -> { adr = pc; pc = (pc + 1) and 0xFFFF }
            A_ZP  -> { adr = l8(pc); pc = (pc + 1) and 0xFFFF }
            A_ZPX -> { adr = (l8(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF }
            A_ZPY -> { adr = (l8(pc) + y) and 0xFF; pc = (pc + 1) and 0xFFFF }
            A_ABS -> { adr = l16(pc); pc = (pc + 2) and 0xFFFF }
            A_ABSX -> {
                val b = l16(pc); pc = (pc + 2) and 0xFFFF
                adr = (b + x) and 0xFFFF
                sautPage = (b and 0xFF00) != (adr and 0xFF00)
            }
            A_ABSY -> {
                val b = l16(pc); pc = (pc + 2) and 0xFFFF
                adr = (b + y) and 0xFFFF
                sautPage = (b and 0xFF00) != (adr and 0xFF00)
            }
            A_INDX -> {
                val z = (l8(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF
                adr = l8(z) or (l8((z + 1) and 0xFF) shl 8)
            }
            A_INDY -> {
                val z = l8(pc); pc = (pc + 1) and 0xFFFF
                val b = l8(z) or (l8((z + 1) and 0xFF) shl 8)
                adr = (b + y) and 0xFFFF
                sautPage = (b and 0xFF00) != (adr and 0xFF00)
            }
            A_REL -> {
                var d = l8(pc); pc = (pc + 1) and 0xFFFF
                if (d and 0x80 != 0) d -= 256
                adr = (pc + d) and 0xFFFF
            }
            A_IND -> {
                val ptr = l16(pc); pc = (pc + 2) and 0xFFFF
                adr = l16Bug(ptr)
            }
        }
    }

    private fun branche(cond: Boolean) {
        if (cond) {
            cycles += if ((adr and 0xFF00) != (pc and 0xFF00)) 2 else 1
            pc = adr
        }
    }

    /** Execute une instruction. Renvoie les cycles consommes. */
    fun pas(): Int {
        if (nmiDemande) { nmiDemande = false; nmi(); return 7 }
        if (irqDemande && (p and F_I) == 0) { irqDemande = false; irq(); return 7 }

        val debut = cycles
        val op = l8(pc)
        pc = (pc + 1) and 0xFFFF
        val m = OP_NOM[op]
        val mode = OP_MODE[op]
        cycles += OP_CYCLES[op]
        adresser(mode)
        if (OP_PENALITE[op] == 1 && sautPage) cycles += 1

        when (m) {
            M_LDA -> { a = l8(adr); majZN(a) }
            M_LDX -> { x = l8(adr); majZN(x) }
            M_LDY -> { y = l8(adr); majZN(y) }
            M_STA -> e8(adr, a)
            M_STX -> e8(adr, x)
            M_STY -> e8(adr, y)
            M_ORA -> { a = a or l8(adr); majZN(a) }
            M_AND -> { a = a and l8(adr); majZN(a) }
            M_EOR -> { a = a xor l8(adr); majZN(a) }
            M_ADC -> opAdc(l8(adr))
            M_SBC, M_SBC_NON_DOC -> opAdc(l8(adr) xor 0xFF)
            M_CMP -> opCmp(a, l8(adr))
            M_CPX -> opCmp(x, l8(adr))
            M_CPY -> opCmp(y, l8(adr))
            M_BIT -> {
                val v = l8(adr)
                set(F_Z, (a and v) == 0)
                set(F_N, v and 0x80 != 0)
                set(F_V, v and 0x40 != 0)
            }
            M_ASL -> if (mode == A_ACC) a = opAsl(a) else e8(adr, opAsl(l8(adr)))
            M_LSR -> if (mode == A_ACC) a = opLsr(a) else e8(adr, opLsr(l8(adr)))
            M_ROL -> if (mode == A_ACC) a = opRol(a) else e8(adr, opRol(l8(adr)))
            M_ROR -> if (mode == A_ACC) a = opRor(a) else e8(adr, opRor(l8(adr)))
            M_INC -> { val v = (l8(adr) + 1) and 0xFF; e8(adr, v); majZN(v) }
            M_DEC -> { val v = (l8(adr) - 1) and 0xFF; e8(adr, v); majZN(v) }
            M_INX -> { x = (x + 1) and 0xFF; majZN(x) }
            M_INY -> { y = (y + 1) and 0xFF; majZN(y) }
            M_DEX -> { x = (x - 1) and 0xFF; majZN(x) }
            M_DEY -> { y = (y - 1) and 0xFF; majZN(y) }
            M_TAX -> { x = a; majZN(x) }
            M_TAY -> { y = a; majZN(y) }
            M_TXA -> { a = x; majZN(a) }
            M_TYA -> { a = y; majZN(a) }
            M_TSX -> { x = sp; majZN(x) }
            M_TXS -> sp = x                       // TXS ne touche pas aux indicateurs
            M_CLC -> set(F_C, false)
            M_SEC -> set(F_C, true)
            M_CLI -> set(F_I, false)
            M_SEI -> set(F_I, true)
            M_CLV -> set(F_V, false)
            M_CLD -> set(F_D, false)
            M_SED -> set(F_D, true)
            M_PHA -> push(a)
            M_PHP -> push(p or F_B or F_U)        // PHP pousse toujours B et U a 1
            M_PLA -> { a = pop(); majZN(a) }
            M_PLP -> p = (pop() and F_B.inv()) or F_U
            M_JMP -> pc = adr
            M_JSR -> {
                val r = (pc - 1) and 0xFFFF
                push((r shr 8) and 0xFF); push(r and 0xFF)
                pc = adr
            }
            M_RTS -> pc = ((pop() or (pop() shl 8)) + 1) and 0xFFFF
            M_RTI -> { p = (pop() and F_B.inv()) or F_U; pc = pop() or (pop() shl 8) }
            M_BRK -> {
                pc = (pc + 1) and 0xFFFF
                push((pc shr 8) and 0xFF); push(pc and 0xFF)
                push(p or F_B or F_U)
                set(F_I, true)
                pc = l16(0xFFFE)
            }
            M_BPL -> branche(p and F_N == 0)
            M_BMI -> branche(p and F_N != 0)
            M_BVC -> branche(p and F_V == 0)
            M_BVS -> branche(p and F_V != 0)
            M_BCC -> branche(p and F_C == 0)
            M_BCS -> branche(p and F_C != 0)
            M_BNE -> branche(p and F_Z == 0)
            M_BEQ -> branche(p and F_Z != 0)
            M_NOP, M_NOP_NON_DOC -> {}
            M_LAX -> { a = l8(adr); x = a; majZN(a) }
            M_SAX -> e8(adr, a and x)
            M_SLO -> { val v = opAsl(l8(adr)); e8(adr, v); a = a or v; majZN(a) }
            M_RLA -> { val v = opRol(l8(adr)); e8(adr, v); a = a and v; majZN(a) }
            M_SRE -> { val v = opLsr(l8(adr)); e8(adr, v); a = a xor v; majZN(a) }
            M_RRA -> { val v = opRor(l8(adr)); e8(adr, v); opAdc(v) }
            M_DCP -> { val v = (l8(adr) - 1) and 0xFF; e8(adr, v); opCmp(a, v) }
            M_ISB -> { val v = (l8(adr) + 1) and 0xFF; e8(adr, v); opAdc(v xor 0xFF) }
            else -> {}
        }
        return (cycles - debut).toInt()
    }
}
