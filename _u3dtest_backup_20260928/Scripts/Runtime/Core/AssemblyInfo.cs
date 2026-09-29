using System.Runtime.CompilerServices;

// 程序集边界声明：让「谁可以访问谁」不只是纪律，而是编译器强制。
// 这些声明本身不改任何运行逻辑，只是把内部实现封起来。
[assembly: InternalsVisibleTo("AgvDemo.Warehouse.Application")]
[assembly: InternalsVisibleTo("AgvDemo.Warehouse.Infrastructure")]
[assembly: InternalsVisibleTo("AgvDemo.Gameplay")]
[assembly: InternalsVisibleTo("AgvDemo.Editor")]
[assembly: InternalsVisibleTo("AgvDemo.Tests")]
