using AgvDemo.Warehouse.Domain;

namespace AgvDemo.Warehouse.Messages
{
    /// <summary>派单成功。领域事件只描述「发生了什么」，不关心谁在监听。</summary>
    public readonly struct AgvTaskAssigned
    {
        public readonly int TaskNo;
        public readonly string TargetLabel;

        public AgvTaskAssigned(int taskNo, string targetLabel)
        {
            TaskNo = taskNo;
            TargetLabel = targetLabel;
        }
    }

    /// <summary>无法通行。</summary>
    public readonly struct AgvBlocked
    {
        public readonly string Reason;

        public AgvBlocked(string reason) => Reason = reason;
    }
}
