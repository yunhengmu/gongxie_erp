import type { EntityDto, PagedAndSortedResultRequestDto } from '@abp/ng.core';

export interface CreateUpdateWarehouseDto {
  code: string;
  name: string;
}

export interface GetWarehousesInput extends PagedAndSortedResultRequestDto {
  filter?: string | null;
}

export interface WarehouseDto extends EntityDto<string> {
  code?: string;
  name?: string;
  isActive?: boolean;
}
