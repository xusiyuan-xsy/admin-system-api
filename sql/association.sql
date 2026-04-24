drop table if exists dating_package;
create table dating_package (
    package_id        bigint(20)      not null auto_increment              comment '套餐ID',
    package_name      varchar(20)     not null                             comment '套餐名称',
    package_type      char(1)         not null default '0'                 comment '套餐类型（0基础套餐 ...）',
    price             decimal(10, 2)  not null                             comment '套餐价格',
    limit_expire      char(1)         not null default '0'                 comment '是否限制服务天数（0否 1是）',
    service_duration  int(11)         not null default 0                   comment '服务时长（天）',
    limit_service_times char(1)         not null default '0'                 comment '是否限制服务次数（0否 1是）',
    service_times     int(11)         not null default 0                   comment '服务次数（次）',
    description       varchar(500)    default ''                           comment '套餐介绍',
    cover_image       varchar(255)    default ''                           comment '套餐封面',
    sort_order        int(4)          not null default 0                   comment '排序权重(越小越靠前)',
    status            char(1)         not null default '0'                 comment '套餐状态（0下架 1上架）',
    del_flag          char(1)         not null default '0'                 comment '删除标志（0存在 2删除）',
    create_by         varchar(64)     not null default ''                  comment '创建者',
    create_time       datetime        not null                             comment '创建时间',
    update_by         varchar(64)     not null default ''                  comment '更新者',
    update_time       datetime        not null default current_timestamp   comment '更新时间',
    primary key (package_id)
) engine=innodb comment = '套餐表';