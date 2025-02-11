// SPDX-License-Identifier: Apache-2.0

package chisel3

import scala.language.experimental.macros

import firrtl.{ir => fir}

import chisel3.internal._
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl._
import chisel3.internal.sourceinfo.{SourceInfo, SourceInfoTransform, UnlocatableSourceInfo, MemTransform}


object Mem {

  /** Creates a combinational/asynchronous-read, sequential/synchronous-write [[Mem]].
    *
    * @param size number of elements in the memory
    * @param t data type of memory element
    */
  def apply[T <: Data](size: BigInt, t: T): Mem[T] = macro MemTransform.apply[T]

  /** Creates a combinational/asynchronous-read, sequential/synchronous-write [[Mem]].
    *
    * @param size number of elements in the memory
    * @param t data type of memory element
    */
  def apply[T <: Data](size: Int, t: T): Mem[T] = macro MemTransform.apply[T]

  /** @group SourceInfoTransformMacro */
  def do_apply[T <: Data](size: BigInt, t: T)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): Mem[T] = {
    if (compileOptions.declaredTypeMustBeUnbound) {
      requireIsChiselType(t, "memory type")
    }
    val mt  = t.cloneTypeFull
    val mem = new Mem(mt, size)
    mt.bind(MemTypeBinding(mem))
    pushCommand(DefMemory(sourceInfo, mem, mt, size))
    mem
  }

  /** @group SourceInfoTransformMacro */
  def do_apply[T <: Data](size: Int, t: T)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): Mem[T] =
    do_apply(BigInt(size), t)(sourceInfo, compileOptions)
}

sealed abstract class MemBase[T <: Data](val t: T, val length: BigInt) extends HasId with NamedComponent with SourceInfoDoc {
  _parent.foreach(_.addId(this))

  // REVIEW TODO: make accessors (static/dynamic, read/write) combinations consistent.

  /** Creates a read accessor into the memory with static addressing. See the
    * class documentation of the memory for more detailed information.
    */
  def apply(x: BigInt): T = macro SourceInfoTransform.xArg

  /** Creates a read accessor into the memory with static addressing. See the
    * class documentation of the memory for more detailed information.
    */
  def apply(x: Int): T = macro SourceInfoTransform.xArg

  /** @group SourceInfoTransformMacro */
  def do_apply(idx: BigInt)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): T = {
    require(idx >= 0 && idx < length)
    apply(idx.asUInt)
  }

  /** @group SourceInfoTransformMacro */
  def do_apply(idx: Int)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): T =
    do_apply(BigInt(idx))(sourceInfo, compileOptions)

  /** Creates a read/write accessor into the memory with dynamic addressing.
    * See the class documentation of the memory for more detailed information.
    */
  def apply(x: UInt): T = macro SourceInfoTransform.xArg

  /** @group SourceInfoTransformMacro */
  def do_apply(idx: UInt)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): T =
    makePort(sourceInfo, idx, MemPortDirection.INFER)

  /** Creates a read accessor into the memory with dynamic addressing. See the
    * class documentation of the memory for more detailed information.
    */
  def read(x: UInt): T = macro SourceInfoTransform.xArg

  /** @group SourceInfoTransformMacro */
  def do_read(idx: UInt)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): T =
    makePort(sourceInfo, idx, MemPortDirection.READ)

  /** Creates a write accessor into the memory.
    *
    * @param idx memory element index to write into
    * @param data new data to write
    */
  def write(idx: UInt, data: T)(implicit compileOptions: CompileOptions): Unit = {
    implicit val sourceInfo = UnlocatableSourceInfo
    makePort(UnlocatableSourceInfo, idx, MemPortDirection.WRITE) := data
  }

  /** Creates a masked write accessor into the memory.
    *
    * @param idx memory element index to write into
    * @param data new data to write
    * @param mask write mask as a Seq of Bool: a write to the Vec element in
    * memory is only performed if the corresponding mask index is true.
    *
    * @note this is only allowed if the memory's element data type is a Vec
    */
  def write(idx: UInt, data: T, mask: Seq[Bool]) (implicit evidence: T <:< Vec[_], compileOptions: CompileOptions): Unit = {
    implicit val sourceInfo = UnlocatableSourceInfo
    val accessor = makePort(sourceInfo, idx, MemPortDirection.WRITE).asInstanceOf[Vec[Data]]
    val dataVec = data.asInstanceOf[Vec[Data]]
    if (accessor.length != dataVec.length) {
      Builder.error(s"Mem write data must contain ${accessor.length} elements (found ${dataVec.length})")
    }
    if (accessor.length != mask.length) {
      Builder.error(s"Mem write mask must contain ${accessor.length} elements (found ${mask.length})")
    }
    for (((cond, port), datum) <- mask zip accessor zip dataVec)
      when (cond) { port := datum }
  }

  private def makePort(sourceInfo: SourceInfo, idx: UInt, dir: MemPortDirection)(implicit compileOptions: CompileOptions): T = {
    requireIsHardware(idx, "memory port index")
    val i = Vec.truncateIndex(idx, length)(sourceInfo, compileOptions)

    val port = pushCommand(
      DefMemPort(sourceInfo,
       t.cloneTypeFull, Node(this), dir, i.ref, Builder.forcedClock.ref)
    ).id
    // Bind each element of port to being a MemoryPort
    port.bind(MemoryPortBinding(Builder.forcedUserModule, Builder.currentWhen))
    port
  }
}

/** A combinational/asynchronous-read, sequential/synchronous-write memory.
  *
  * Writes take effect on the rising clock edge after the request. Reads are
  * combinational (requests will return data on the same cycle).
  * Read-after-write hazards are not an issue.
  *
  * @note when multiple conflicting writes are performed on a Mem element, the
  * result is undefined (unlike Vec, where the last assignment wins)
  */
sealed class Mem[T <: Data] private (t: T, length: BigInt) extends MemBase(t, length)

object SyncReadMem {


  type ReadUnderWrite = fir.ReadUnderWrite.Value
  val Undefined = fir.ReadUnderWrite.Undefined
  val ReadFirst = fir.ReadUnderWrite.Old
  val WriteFirst = fir.ReadUnderWrite.New

  /** Creates a sequential/synchronous-read, sequential/synchronous-write [[SyncReadMem]].
    *
    * @param size number of elements in the memory
    * @param t data type of memory element
    */
  def apply[T <: Data](size: BigInt, t: T): SyncReadMem[T] = macro MemTransform.apply[T]
  def apply[T <: Data](size: BigInt, t: T, ruw: ReadUnderWrite): SyncReadMem[T] = macro MemTransform.apply_ruw[T]

  /** Creates a sequential/synchronous-read, sequential/synchronous-write [[SyncReadMem]].
    *
    * @param size number of elements in the memory
    * @param t data type of memory element
    */
  def apply[T <: Data](size: Int, t: T): SyncReadMem[T] = macro MemTransform.apply[T]
  def apply[T <: Data](size: Int, t: T, ruw: ReadUnderWrite): SyncReadMem[T] = macro MemTransform.apply_ruw[T]

  /** @group SourceInfoTransformMacro */
  def do_apply[T <: Data](size: BigInt, t: T, ruw: ReadUnderWrite = Undefined)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): SyncReadMem[T] = {
    if (compileOptions.declaredTypeMustBeUnbound) {
      requireIsChiselType(t, "memory type")
    }
    val mt  = t.cloneTypeFull
    val mem = new SyncReadMem(mt, size, ruw)
    mt.bind(MemTypeBinding(mem))
    pushCommand(DefSeqMemory(sourceInfo, mem, mt, size, ruw))
    mem
  }

  /** @group SourceInfoTransformMacro */
  // Alternate signatures can't use default parameter values
  def do_apply[T <: Data](size: Int, t: T)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): SyncReadMem[T] =
    do_apply(BigInt(size), t)(sourceInfo, compileOptions)

  /** @group SourceInfoTransformMacro */
  // Alternate signatures can't use default parameter values
  def do_apply[T <: Data](size: Int, t: T, ruw: ReadUnderWrite)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): SyncReadMem[T] =
    do_apply(BigInt(size), t, ruw)(sourceInfo, compileOptions)
}

/** A sequential/synchronous-read, sequential/synchronous-write memory.
  *
  * Writes take effect on the rising clock edge after the request. Reads return
  * data on the rising edge after the request. Read-after-write behavior (when
  * a read and write to the same address are requested on the same cycle) is
  * undefined.
  *
  * @note when multiple conflicting writes are performed on a Mem element, the
  * result is undefined (unlike Vec, where the last assignment wins)
  */
sealed class SyncReadMem[T <: Data] private (t: T, n: BigInt, val readUnderWrite: SyncReadMem.ReadUnderWrite) extends MemBase[T](t, n) {
  def read(x: UInt, en: Bool): T = macro SourceInfoTransform.xEnArg

  /** @group SourceInfoTransformMacro */
  def do_read(addr: UInt, enable: Bool)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions): T = {
    val a = Wire(UInt())
    a := DontCare
    var port: Option[T] = None
    when (enable) {
      a := addr
      port = Some(super.do_read(a))
    }
    port.get
  }

  /** @group SourceInfoTransformMacro*/
  override def do_read(idx: UInt)(implicit sourceInfo: SourceInfo, compileOptions: CompileOptions) =
    do_read(addr = idx, enable = true.B)
  // note: we implement do_read(addr) for SyncReadMem in terms of do_read(addr, en) in order to ensure that
  //       `mem.read(addr)` will always behave the same as `mem.read(addr, true.B)`
}
