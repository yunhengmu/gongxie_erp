using System.Collections.Generic;
using AgvDemo.Core.Domain;
using NUnit.Framework;

namespace AgvDemo.Core.Tests
{
    public class DeviceStateMachineTests
    {
        static readonly MachineState[] AgvCycle =
        {
            MachineState.Idle, MachineState.Moving, MachineState.Scanning,
            MachineState.Loading, MachineState.Moving, MachineState.Unloading,
        };

        [Test]
        public void DefaultState_IsIdle()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);

            Assert.AreEqual(MachineState.Idle, sm.State);
            Assert.IsTrue(sm.AutoAdvance);
            Assert.AreEqual(0, sm.CompletedCycles);
        }

        [Test]
        public void AutoAdvance_WalksDeclaredCycleAndCountsCompletion()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);
            var seen = new List<MachineState>();

            int guard = 0;
            while (sm.CompletedCycles == 0 && guard++ < 100000)
            {
                var state = sm.Tick(0.01f);
                if (sm.CompletedCycles > 0) break; // 完成整周的那一帧属于下一轮，不计入
                if (seen.Count == 0 || seen[seen.Count - 1] != state) seen.Add(state);
            }

            Assert.AreEqual(1, sm.CompletedCycles);
            CollectionAssert.AreEqual(AgvCycle, seen);
        }

        [Test]
        public void ManualMode_IgnoresTickAndStepsOnCommand()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv) { AutoAdvance = false };
            var before = sm.State;

            for (int i = 0; i < 100; i++) sm.Tick(1f);
            Assert.AreEqual(before, sm.State, "手动模式下 Tick 不应推进状态");

            Assert.IsTrue(sm.TryAdvance());
            Assert.AreEqual(MachineState.Moving, sm.State);
        }

        [Test]
        public void TryAdvance_IsRejectedWhileAutoAdvanceIsOn()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);

            Assert.IsFalse(sm.TryAdvance());
            Assert.AreEqual(MachineState.Idle, sm.State);
        }

        [Test]
        public void Progress_ResetsOnStateChangeAndStaysInRange()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);

            sm.Tick(0.6f);
            Assert.Greater(sm.Progress, 0f);
            Assert.LessOrEqual(sm.Progress, 1f);

            sm.Tick(0.6f); // 跨过 Idle 的 1.2s
            Assert.AreEqual(MachineState.Moving, sm.State);
            Assert.Less(sm.Progress, 0.5f);
        }

        [Test]
        public void Fault_HaltsMachineUntilCleared()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);
            sm.RaiseFault("急停被按下");

            Assert.AreEqual(MachineState.Fault, sm.State);
            Assert.AreEqual("急停被按下", sm.FaultReason);

            sm.Tick(10f);
            Assert.AreEqual(MachineState.Fault, sm.State, "故障态不应被 Tick 推动");

            sm.ClearFault();
            Assert.AreEqual(MachineState.Idle, sm.State);
            Assert.IsNull(sm.FaultReason);
        }

        [Test]
        public void Offline_HaltsMachineUntilClearedExternally()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);
            sm.SetOffline();

            sm.Tick(10f);
            Assert.AreEqual(MachineState.Offline, sm.State);

            // 通道恢复后由外部把状态拉回 Idle，状态机自己不负责恢复
            sm.ClearFault();
            Assert.AreEqual(MachineState.Idle, sm.State);
        }

        [Test]
        public void NonPositiveDeltaTime_IsIgnored()
        {
            var sm = new DeviceStateMachine(DeviceKind.Agv);

            sm.Tick(0f);
            sm.Tick(-5f);

            Assert.AreEqual(MachineState.Idle, sm.State);
            Assert.AreEqual(0f, sm.ElapsedInState);
        }
    }
}