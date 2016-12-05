// See LICENSE for license details.

package jtag.test

import Chisel.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}

import chisel3._
import jtag._

trait JtagTestUtilities extends PeekPokeTester[chisel3.Module] with TristateTestUtility {
  val jtag: JtagIO
  val output: JtagOutput
  val status: JtagStatus

  var expectedInstruction: Option[Int] = None  // expected instruction (status.instruction) after TCK low

  /** Convenience function for stepping a JTAG cycle (TCK high -> low -> high) and checking basic
    * JTAG values.
    *
    * @param tms TMS to set after the clock low
    * @param expectedState expected state during this cycle
    * @param tdi TDI to set after the clock low
    * @param expectedTdo expected TDO after the clock low
    */
  def jtagCycle(tms: Int, expectedState: JtagState.State, tdi: TristateValue = X,
      expectedTdo: TristateValue = Z, msg: String = "") {
    expect(jtag.TCK, 1, "TCK must start at 1")
    val prevState = peek(status.state)

    expect(status.state, expectedState, s"$msg: expected state $expectedState")

    poke(jtag.TCK, 0)
    step(1)
    expect(status.state, expectedState, s"$msg: expected state $expectedState")

    poke(jtag.TMS, tms)
    poke(jtag.TDI, tdi)
    expect(jtag.TDO, expectedTdo, s"$msg: TDO")
    expectedInstruction match {
      case Some(instruction) => expect(output.instruction, instruction, s"$msg: expected instruction $instruction")
      case None =>
    }

    poke(jtag.TCK, 1)
    step(1)
    expect(jtag.TDO, expectedTdo, s"$msg: TDO")
  }

  /** After every TCK falling edge following this call, expect this instruction on the status line.
    * None means to not check the instruction output.
    */
  def expectInstruction(expected: Option[Int]) {
    expectedInstruction = expected
  }

  /** Resets the test block using 5 TMS transitions
    */
  def tmsReset() {
    poke(jtag.TMS, 1)
    poke(jtag.TCK, 1)
    step(1)
    for (_ <- 0 until 5) {
      poke(jtag.TCK, 0)
      step(1)
      poke(jtag.TCK, 1)
      step(1)
    }
    expect(status.state, JtagState.TestLogicReset, "TMS reset: expected in reset state")
  }

  def resetToIdle() {
    tmsReset()
    jtagCycle(0, JtagState.TestLogicReset)
    expect(status.state, JtagState.RunTestIdle)
  }

  def idleToDRShift() {
    jtagCycle(1, JtagState.RunTestIdle)
    jtagCycle(0, JtagState.SelectDRScan)
    jtagCycle(0, JtagState.CaptureDR)
    expect(status.state, JtagState.ShiftDR)
  }

  def idleToIRShift() {
    jtagCycle(1, JtagState.RunTestIdle)
    jtagCycle(1, JtagState.SelectDRScan)
    jtagCycle(0, JtagState.SelectIRScan)
    jtagCycle(0, JtagState.CaptureIR)
    expect(status.state, JtagState.ShiftIR)
  }

  def drShiftToIdle() {
    jtagCycle(1, JtagState.Exit1DR)
    jtagCycle(0, JtagState.UpdateDR)
    expect(status.state, JtagState.RunTestIdle)
  }

  def irShiftToIdle() {
    jtagCycle(1, JtagState.Exit1IR)
    jtagCycle(0, JtagState.UpdateIR)
    expect(status.state, JtagState.RunTestIdle)
  }

  /** Shifts data into the TDI register and checks TDO against expected data. Must start in the
    * shift, and the TAP controller will be in the Exit1 state afterwards.
    *
    * TDI and expected TDO are specified as a string of 0 and 1. The strings are in time order,
    * the first elements are the ones shifted out (and expected in) first. This is in waveform
    * display order and LSB-first order (so when specifying a number in the usual MSB-first order,
    * the string needs to be reversed).
    */
  def shift(tdi: String, expectedTdo: String, expectedState: JtagState.State, expectedNextState: JtagState.State) {
    def charToTristate(x: Char): TristateValue = x match {
      case '0' => 0
      case '1' => 1
      case '?' => X
    }

    val tdiBits = tdi map charToTristate
    val expectedTdoBits = expectedTdo map charToTristate

    require(tdi.size == expectedTdo.size)
    val zipBits = tdiBits zip expectedTdoBits

    for ((tdiBit, expectedTdoBit) <- zipBits.init) {
      jtagCycle(0, expectedState, tdi=tdiBit, expectedTdo=expectedTdoBit)
    }
    val (tdiLastBit, expectedTdoLastBit) = zipBits.last
    jtagCycle(1, expectedState, tdi=tdiLastBit, expectedTdo=expectedTdoLastBit)

    expect(status.state, expectedNextState)
  }

  def drShift(tdi: String, expectedTdo: String) {
    shift(tdi, expectedTdo, JtagState.ShiftDR, JtagState.Exit1DR)
  }

  def irShift(tdi: String, expectedTdo: String) {
    shift(tdi, expectedTdo, JtagState.ShiftIR, JtagState.Exit1IR)
  }
}

class JtagTapTester(val c: JtagTapModule) extends PeekPokeTester(c) with JtagTestUtilities {
  import BinaryParse._

  val jtag = c.io.jtag
  val output = c.io.output
  val status = c.io.status

  tmsReset()
  expectInstruction(Some("11".b))
  // Test sequence in Figure 6-3 (instruction scan), starting with the half-cycle off-screen
  jtagCycle(1, JtagState.TestLogicReset)
  jtagCycle(0, JtagState.TestLogicReset)
  jtagCycle(1, JtagState.RunTestIdle)
  jtagCycle(1, JtagState.SelectDRScan)
  jtagCycle(0, JtagState.SelectIRScan)
  jtagCycle(0, JtagState.CaptureIR)
  jtagCycle(0, JtagState.ShiftIR, tdi=0, expectedTdo=1)  // first two required IR capture bits
  jtagCycle(1, JtagState.ShiftIR, tdi=0, expectedTdo=0)
  jtagCycle(0, JtagState.Exit1IR)
  jtagCycle(0, JtagState.PauseIR)
  jtagCycle(0, JtagState.PauseIR)
  jtagCycle(1, JtagState.PauseIR)
  jtagCycle(0, JtagState.Exit2IR)
  jtagCycle(0, JtagState.ShiftIR, tdi=1, expectedTdo=0)
  jtagCycle(0, JtagState.ShiftIR, tdi=1, expectedTdo=0)
  jtagCycle(0, JtagState.ShiftIR, tdi=0, expectedTdo=1)
  jtagCycle(1, JtagState.ShiftIR, tdi=1, expectedTdo=1)
  jtagCycle(1, JtagState.Exit1IR)
  expectInstruction(Some("10".b))
  jtagCycle(0, JtagState.UpdateIR)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)

  tmsReset()
  expectInstruction(Some("11".b))
  jtagCycle(0, JtagState.TestLogicReset)
  // Test sequence in Figure 6-4 (data scan), starting with the half-cycle off-screen
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(1, JtagState.RunTestIdle)
  jtagCycle(0, JtagState.SelectDRScan)
  jtagCycle(0, JtagState.CaptureDR)
  jtagCycle(0, JtagState.ShiftDR, tdi=1, expectedTdo=0)  // required bypass capture bit
  jtagCycle(0, JtagState.ShiftDR, tdi=0, expectedTdo=1)
  jtagCycle(1, JtagState.ShiftDR, tdi=1, expectedTdo=0)
  jtagCycle(0, JtagState.Exit1DR)
  jtagCycle(0, JtagState.PauseDR)
  jtagCycle(0, JtagState.PauseDR)
  jtagCycle(1, JtagState.PauseDR)
  jtagCycle(0, JtagState.Exit2DR)
  jtagCycle(0, JtagState.ShiftDR, tdi=1, expectedTdo=1)
  jtagCycle(0, JtagState.ShiftDR, tdi=1, expectedTdo=1)
  jtagCycle(0, JtagState.ShiftDR, tdi=0, expectedTdo=1)
  jtagCycle(1, JtagState.ShiftDR, tdi=0, expectedTdo=0)
  jtagCycle(1, JtagState.Exit1DR)
  jtagCycle(0, JtagState.UpdateDR)
  jtagCycle(0, JtagState.RunTestIdle)
  jtagCycle(1, JtagState.RunTestIdle)
  jtagCycle(1, JtagState.SelectDRScan)  // Fig 6-4 says "Select-IR-Scan", seems like a typo
  jtagCycle(1, JtagState.SelectIRScan)
  jtagCycle(1, JtagState.TestLogicReset)
  jtagCycle(1, JtagState.TestLogicReset)
  jtagCycle(1, JtagState.TestLogicReset)
}

/** Test utility module that handles the multiclock generation for a JTAG test harness.
  * @param tapGenerator a function that generates the JTAG tap
  */
class JtagTapModule(irLength: Int, tapGenerator: (Int)=>JtagTapController) extends Module {
  class ModIO extends Bundle {
    val jtag = new JtagIO
    val output = new JtagOutput(irLength)
    val status = new JtagStatus(irLength)
  }

  class JtagTapClocked (modClock: Clock) extends Module(override_clock=Some(modClock)) {
    val io = IO(new ModIO)

    val tap = tapGenerator(irLength)
    io.jtag <> tap.io.jtag
    io.output <> tap.io.output
    io.status <> tap.io.status
  }

  val io = IO(new ModIO)

  val tap = Module(new JtagTapClocked(io.jtag.TCK.asClock))
  io <> tap.io
}

class JtagTapExampleWaveformSpec extends ChiselFlatSpec {
  "JTAG TAP with example waveforms from the spec" should "work" in {
    def bareJtagGenerator(irLength: Int): JtagTapController = {
      JtagTapGenerator(irLength, Map())
    }

    //Driver(() => new JtagTap(2)) {  // multiclock doesn't work here yet
    Driver(() => new JtagTapModule(2, bareJtagGenerator), backendType="verilator") {
      c => new JtagTapTester(c)
    } should be (true)
  }
}
