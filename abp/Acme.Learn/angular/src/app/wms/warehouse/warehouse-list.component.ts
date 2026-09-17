// 从 @angular/core 引入：Component 装饰器、OnInit 生命周期接口、inject 依赖注入函数
import { Component, OnInit, inject } from '@angular/core';
// 代理统一从 barrel 出（@proxy/wms/warehouse/index.ts 里已 export * from './models'）
import { WarehouseService, WarehouseDto } from '@proxy/wms/warehouse';

@Component({
    selector: 'app-warehouse-list',
    templateUrl: './warehouse-list.component.html',
})
export class WarehouseListComponent implements OnInit {
    // inject 注入函数，等价于 Spring 的 @Autowired
    private warehouseService = inject(WarehouseService);

    // 列表数据。拿到后有值，Angular 自动重渲染表格
    warehouses: WarehouseDto[] = [];

    // 总条数，给分页用
    totalCount = 0;

    // Angular 生命周期钩子：组件初始化完成后自动调用一次（≈ Vue onMounted / Spring @PostConstruct）
    ngOnInit(): void {
        this.load();                    // ← 补 this.
    }

    // 自己定义的普通方法，名字任意。抽出来是为了删完记录后能复用
    load(): void {
        // getList 返回 Observable（≈ Reactor Flux），subscribe 之后不阻塞，数据在回调里到
        this.warehouseService.getList({
            skipCount: 0,               // 跳过多少条（第几页 × 每页条数）
            maxResultCount: 10,         // 本页取几条
        }).subscribe(res => {
            this.warehouses = res.items ?? [];      // ← 原 res.items
            this.totalCount = res.totalCount ?? 0;
        });
    }
}
