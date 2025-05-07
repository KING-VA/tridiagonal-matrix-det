package tridiagonal-matrix-det

import chisel3._
import chisel3.util._


class DMAOutputBuffer(beatBytes: Int) extends Module {
  val io = IO(new Bundle {
    val dmaInput = Flipped(Decoupled(UInt((beatBytes * 8).W)))
    val dataOut = Decoupled(UInt(256.W))
  })

  val bitsFilled = RegInit(0.U(log2Ceil(256 + 1).W))
  val wideData = RegInit(0.U(256.W))

  // Input logic: accumulate input data into wideData
  when(io.dmaInput.fire()) {
    wideData := (io.dmaInput.bits << bitsFilled) | wideData
    bitsFilled := bitsFilled + (beatBytes * 8).U
  }

  // Reverse bytes for endian conversion
  def reverseBytes(data: UInt): UInt = {
    Cat((0 until 32).reverse.map(i => data(8*(i+1)-1, 8*i)))
  }

  val reversedData = reverseBytes(wideData)

  // Output logic: fire only when we have 256 bits
  io.dataOut.valid := bitsFilled === 256.U
  io.dataOut.bits := reversedData

  // Only shift out data when consumer accepts it
  when(io.dataOut.fire()) {
    wideData := 0.U
    bitsFilled := 0.U
  }

  // Allow DMA input if there's room for at least one beat
  io.dmaInput.ready := (bitsFilled + (beatBytes * 8).U) <= 256.U
}