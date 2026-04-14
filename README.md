

# **RuoYi v3.9.2**

#### 基于SpringBoot+Vue前后端分离的Java快速开发框架



mysql -u root -p123456 ry-vue -e "source D:/xsy/newElement/admin-system-api/sql/matchmaking_profile.sql"



## 平台简介

若依是一套全部开源的快速开发平台，毫无保留给个人及企业免费使用。

- 前端采用Vue、Element UI。
- 后端采用Spring Boot、Spring Security、Redis & Jwt。
- 权限认证使用Jwt，支持多终端认证系统。
- 支持加载动态权限菜单，多方式轻松权限控制。
- 高效率开发，使用代码生成器可以一键生成前后端代码。
- 阿里云折扣场：[点我进入](http://aly.ruoyi.vip)，腾讯云秒杀场：[点我进入](http://txy.ruoyi.vip)

  

# 版本分支

RuoYi-Vue 后端项目提供 Spring Boot 2.x / 3.x / 4.x 多版本分支的并行维护。


| 名称             | 说明                        | 地址                                                                                                               |
| -------------- | ------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| master 默认分支    | Spring Boot 4.x (JDK 17+) | [https://gitee.com/y_project/RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue)                                   |
| springboot3 分支 | Spring Boot 3.x (JDK 17+) | [https://gitee.com/y_project/RuoYi-Vue/tree/springboot3](https://gitee.com/y_project/RuoYi-Vue/tree/springboot3) |
| springboot2 分支 | Spring Boot 2.x (JDK 8+)  | [https://gitee.com/y_project/RuoYi-Vue/tree/springboot2](https://gitee.com/y_project/RuoYi-Vue/tree/springboot2) |


RuoYi-Vue 前端项目提供 Vue 2.x / 3.x / JavaScript TypeScript 版本均可混用搭配


| 项目名称       | **RuoYi-Vue**                                      | **RuoYi-Vue3**                                             | **RuoYi-Vue3-TypeScript**                                                             |
| ---------- | -------------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| **前端框架**   | Vue 2                                              | Vue 3                                                      | Vue 3                                                                                 |
| **脚本语言**   | JavaScript                                         | JavaScript                                                 | TypeScript                                                                            |
| **构建工具**   | Vue CLI                                            | Vite                                                       | Vite                                                                                  |
| **UI 组件库** | Element UI                                         | Element Plus                                               | Element Plus                                                                          |
| **状态管理**   | Vuex                                               | Pinia                                                      | Pinia                                                                                 |
| **路由管理**   | Vue Router 3                                       | Vue Router 4                                               | Vue Router 4                                                                          |
| **核心特点**   | 1. 技术栈经典稳定 2. 社区资料丰富 3. 当前维护重心已转移                  | 1. 现代前端技术栈 2. 开发体验与性能更优 3. 官方主推的活跃版本                       | 1. 类型加持，减少沟通成本 2. 开发时有提示，效率更高 3. 多人协作企业级开发项目                                          |
| **仓库地址**   | [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) | [RuoYi-Vue3](https://gitcode.com/yangzongzhuan/RuoYi-Vue3) | [RuoYi-Vue3-TypeScript](https://gitcode.com/yangzongzhuan/RuoYi-Vue3/tree/typescript) |


## 内置功能

1. 用户管理：用户是系统操作者，该功能主要完成系统用户配置。
2. 部门管理：配置系统组织机构（公司、部门、小组），树结构展现支持数据权限。
3. 岗位管理：配置系统用户所属担任职务。
4. 菜单管理：配置系统菜单，操作权限，按钮权限标识等。
5. 角色管理：角色菜单权限分配、设置角色按机构进行数据范围权限划分。
6. 字典管理：对系统中经常使用的一些较为固定的数据进行维护。
7. 参数管理：对系统动态配置常用参数。
8. 通知公告：系统通知公告信息发布维护。
9. 操作日志：系统正常操作日志记录和查询；系统异常信息日志记录和查询。
10. 登录日志：系统登录日志记录查询包含登录异常。
11. 在线用户：当前系统中活跃用户状态监控。
12. 定时任务：在线（添加、修改、删除)任务调度包含执行结果日志。
13. 代码生成：前后端代码的生成（java、html、xml、sql）支持CRUD下载 。
14. 系统接口：根据业务代码自动生成相关的api接口文档。
15. 服务监控：监视当前系统CPU、内存、磁盘、堆栈等相关信息。
16. 缓存监控：对系统的缓存信息查询，命令统计等。
17. 在线构建器：拖动表单元素生成相应的HTML代码。
18. 连接池监视：监视当前系统数据库连接池状态，可进行分析SQL找出系统性能瓶颈。

## 在线体验

- admin/admin123  
- 陆陆续续收到一些打赏，为了更好的体验已用于演示服务器升级。谢谢各位小伙伴。

演示地址：[http://vue.ruoyi.vip](http://vue.ruoyi.vip)  
文档地址：[http://doc.ruoyi.vip](http://doc.ruoyi.vip)

## 演示图


|     |     |
| --- | --- |
|     |     |
|     |     |
|     |     |
|     |     |
|     |     |
|     |     |
|     |     |
|     |     |


## 若依前后端分离交流群

QQ群： [加入QQ群](https://jq.qq.com/?_wv=1027&k=5bVB1og) [加入QQ群](https://jq.qq.com/?_wv=1027&k=5eiA4DH) [加入QQ群](https://jq.qq.com/?_wv=1027&k=5AxMKlC) [加入QQ群](https://jq.qq.com/?_wv=1027&k=51G72yr) [加入QQ群](https://jq.qq.com/?_wv=1027&k=VvjN2nvu) [加入QQ群](https://jq.qq.com/?_wv=1027&k=5vYAqA05) [加入QQ群](https://jq.qq.com/?_wv=1027&k=kOIINEb5) [加入QQ群](https://jq.qq.com/?_wv=1027&k=UKtX5jhs) [加入QQ群](https://jq.qq.com/?_wv=1027&k=EI9an8lJ) [加入QQ群](https://jq.qq.com/?_wv=1027&k=SWCtLnMz) [加入QQ群](https://jq.qq.com/?_wv=1027&k=96Dkdq0k) [加入QQ群](https://jq.qq.com/?_wv=1027&k=0fsNiYZt) [加入QQ群](https://jq.qq.com/?_wv=1027&k=7xw4xUG1) [加入QQ群](https://jq.qq.com/?_wv=1027&k=eCx8eyoJ) [加入QQ群](https://jq.qq.com/?_wv=1027&k=SpyH2875) [加入QQ群](https://jq.qq.com/?_wv=1027&k=tKEt51dz) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=0vBbSb0ztbBgVtn3kJS-Q4HUNYwip89G&authKey=8irq5PhutrZmWIvsUsklBxhj57l%2F1nOZqjzigkXZVoZE451GG4JHPOqW7AW6cf0T&noverify=0&group_code=143961921) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=ZFAPAbp09S2ltvwrJzp7wGlbopsc0rwi&authKey=HB2cxpxP2yspk%2Bo3WKTBfktRCccVkU26cgi5B16u0KcAYrVu7sBaE7XSEqmMdFQp&noverify=0&group_code=174951577) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=Fn2aF5IHpwsy8j6VlalNJK6qbwFLFHat&authKey=uyIT%2B97x2AXj3odyXpsSpVaPMC%2Bidw0LxG5MAtEqlrcBcWJUA%2FeS43rsF1Tg7IRJ&noverify=0&group_code=161281055) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=XIzkm_mV2xTsUtFxo63bmicYoDBA6Ifm&authKey=dDW%2F4qsmw3x9govoZY9w%2FoWAoC4wbHqGal%2BbqLzoS6VBarU8EBptIgPKN%2FviyC8j&noverify=0&group_code=138988063) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=DkugnCg68PevlycJSKSwjhFqfIgrWWwR&authKey=pR1Pa5lPIeGF%2FFtIk6d%2FGB5qFi0EdvyErtpQXULzo03zbhopBHLWcuqdpwY241R%2F&noverify=0&group_code=151450850) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=F58bgRa-Dp-rsQJThiJqIYv8t4-lWfXh&authKey=UmUs4CVG5OPA1whvsa4uSespOvyd8%2FAr9olEGaWAfdLmfKQk%2FVBp2YU3u2xXXt76&noverify=0&group_code=224622315) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=Nxb2EQ5qozWa218Wbs7zgBnjLSNk_tVT&authKey=obBKXj6SBKgrFTJZx0AqQnIYbNOvBB2kmgwWvGhzxR67RoRr84%2Bus5OadzMcdJl5&noverify=0&group_code=287842588) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=numtK1M_I4eVd2Gvg8qtbuL8JgX42qNh&authKey=giV9XWMaFZTY%2FqPlmWbkB9g3fi0Ev5CwEtT9Tgei0oUlFFCQLDp4ozWRiVIzubIm&noverify=0&group_code=187944233) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=G6r5KGCaa3pqdbUSXNIgYloyb8e0_L0D&authKey=4w8tF1eGW7%2FedWn%2FHAypQksdrML%2BDHolQSx7094Agm7Luakj9EbfPnSTxSi2T1LQ&noverify=0&group_code=228578329) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=GsOo-OLz53J8y_9TPoO6XXSGNRTgbFxA&authKey=R7Uy%2Feq%2BZsoKNqHvRKhiXpypW7DAogoWapOawUGHokJSBIBIre2%2FoiAZeZBSLuBc&noverify=0&group_code=191164766) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=PmYavuzsOthVqfdAPbo4uAeIbu7Ttjgc&authKey=p52l8%2FXa4PS1JcEmS3VccKSwOPJUZ1ZfQ69MEKzbrooNUljRtlKjvsXf04bxNp3G&noverify=0&group_code=174569686) [加入QQ群](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=M9y5NjAl44lAL_Vh2crmEehZU_PMU6KS&authKey=ZSDz8hEREWSaPuxQV3gEwqGIaGjfRNnkB4rJjf0IvXhrSUGSGwQFmBA%2Boe8HFxyl&noverify=0&group_code=127358632) 点击按钮入群。