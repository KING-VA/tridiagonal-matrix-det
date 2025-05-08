package TriDiagMatDet

import chisel3._
import chisel3.util.HasBlackBoxResource
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile.{HasCoreParameters, LazyRoCC, LazyRoCCModuleImp, OpcodeSet}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.SystemBusKey

class TriDiagDet(implicit p: Parameters) extends BlackBox with HasBlackBoxResource {
  val io = IO(new TriDiagDetCoreIO)

  addResource("/vsrc/TriDiagDet.v")
  // addResource("/vsrc/tridiag_det_core.v") -- Just go with flattening in Chisel for now
}

class TriDiagDetAccel(opcodes: OpcodeSet)(implicit p: Parameters) extends LazyRoCC(opcodes = opcodes, nPTWPorts = 0) {
  val dma = LazyModule(new DMA(p(SystemBusKey).beatBytes, 32, "TriDiagDetAccelDMA"))

  override lazy val module = new TriDiagDetAccelImp(this)
  override val tlNode = dma.id_node
}

class TriDiagDetAccelImp(outer: TriDiagDetAccel)(implicit p: Parameters) extends LazyRoCCModuleImp(outer) with HasCoreParameters {
  val beatBytes = p(SystemBusKey).beatBytes

  // RoCC Decoupler
  val dcplr = Module(new RoCCDecoupler)
  dcplr.io.reset     := reset
  dcplr.io.rocc_cmd  <> io.cmd
  io.resp            <> dcplr.io.rocc_resp
  io.busy            := dcplr.io.rocc_busy
  io.interrupt       := dcplr.io.rocc_intr
  dcplr.io.rocc_excp := io.exception

  // Controller
  val ctrl = Module(new TriDiagController(32, beatBytes))
  ctrl.io.reset   := reset
  ctrl.io.dcplrIO <> dcplr.io.ctrlIO

  // DMA Connections
  outer.dma.module.io.read.req <> ctrl.io.dmem.readReq
  outer.dma.module.io.write.req <> ctrl.io.dmem.writeReq
  ctrl.io.dmem.readResp <> outer.dma.module.io.read.resp
  ctrl.io.dmem.readRespQueue <> outer.dma.module.io.read.queue
  ctrl.io.dmem.busy := outer.dma.module.io.readBusy | outer.dma.module.io.writeBusy

  // Tridiagonal Det Core (Black Box)
  val tdetbb = Module(new TriDiagDet)
  tdetbb.io <> ctrl.io.triDiagCoreIO
}