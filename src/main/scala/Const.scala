package TriDiagMatDet

import chisel3._
import chisel3.ChiselEnum

// TriDiag ISA
// If the size is shorter than 16 for READIN, the data is padded with 1s to the left, consistency must be maintained in that A & C should be 1 value shorter than B
// The flow is as follows (loading must be done in this order -- TODO allow any order):
// 1. READIN_A: Load the A array into the accelerator (RS1 = address)
// 2. READIN_C: Load the C array into the accelerator (RS1 = address)
// 3. READIN_B: Load the B array into the accelerator (RS1 = address) 
// For all readins, the controller will read in 256 bits via DMA and then shave the results for A & C to 240 bits (16 bytes) and B to 256 bits (32 bytes) -- firmware will handle the padding
// 4. START_COMP: Start the computation (RS1 = address) - this command will start the computation (the return address is stored in the controller until the core is done and returns the result which then the controller uses to write back the result to the given address)
// QUERYSTATUS: Check the status of the accelerator (Idle, Loaded_A, Loaded_C, Loaded_B, WaitingforStart, Running, Done) - this command will check the status and return the status to RD of the instruction
object TriDiagISA {
  val READIN_A = 0.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val READIN_C = 1.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val READIN_B = 2.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val START_COMP = 4.U(7.W) // RS1 will set the memory location to output the result of the calculation
  val QUERYSTATUS = 5.U(7.W) // The status of the accelerator (Idle, Loaded_A, Loaded_C, Loaded_B, WaitingforStart, Running, Done) will be returned to RD of the command -- See Decoupler for more details
}

// Main Controller States
object CtrlState extends ChiselEnum {
  val sIdle, sASetup, sBSetup, sCSetup, sRun, sDataWrite, sError = Value
}

// Memory Controller States
object MemState extends ChiselEnum {
  val sIdle, sReadReq, sReadIntoAccel, sWriteReq = Value
}

// Create the status enum for the controller
object ControllerStatus {
  val Idle = 0.U(3.W) // Idle state
  val WaitingforStart = 1.U(3.W) // Waiting for start signal
  val Running = 2.U(3.W) // Running the computation
  val Done = 3.U(3.W) // Computation done
  val Error = 4.U(3.W) // Error state
}