using System;
using System.Collections.Generic;

namespace AgvDemo.Core
{
    /// <summary>
    /// 全局事件总线。组件之间不互相持有引用，只发事件 —— 对应 Spring 的 ApplicationEventPublisher。
    /// </summary>
    public static class EventBus
    {
        static readonly Dictionary<Type, Delegate> Handlers = new Dictionary<Type, Delegate>();

        public static void Subscribe<T>(Action<T> handler)
        {
            if (Handlers.TryGetValue(typeof(T), out var d))
                Handlers[typeof(T)] = (Action<T>)d + handler;
            else
                Handlers[typeof(T)] = handler;
        }

        public static void Unsubscribe<T>(Action<T> handler)
        {
            if (!Handlers.TryGetValue(typeof(T), out var d)) return;
            var next = (Action<T>)d - handler;
            if (next == null) Handlers.Remove(typeof(T));
            else Handlers[typeof(T)] = next;
        }

        public static void Publish<T>(T evt)
        {
            if (Handlers.TryGetValue(typeof(T), out var d))
                ((Action<T>)d)?.Invoke(evt);
        }

        public static void Clear() => Handlers.Clear();
    }
}
