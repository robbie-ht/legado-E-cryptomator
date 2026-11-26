# 阅读（legado-E）加密保险库改造说明

> 本次改动只做「加功能」，未改动原有阅读/书源/界面逻辑。原版功能全部保留：
> 添加书源、搜索、换源、朗读、缓存、导出、备份恢复等照常可用。

## 一、需求对照

| 需求 | 实现 |
| --- | --- |
| 1. 添加小说文件后放到程序数据目录下并加密，数据目录中「一个未加密文件 + 一个文件夹（加密数据）」 | `files/novelVault/vault.info`（未加密，仅 KDF 参数/盐/被口令包裹的主密钥）+ `files/novelVault/vault/`（整目录加密数据） |
| 2. 每次打开 App 必须输入密码，输入后解密并显示书架 | 启动入口改为 `UnlockActivity`，解密完成后进书架；首次使用为「设置密码」 |
| 3. 不输入密码绝对看不到书架任何内容（含书名、封面） | 数据库 SQLCipher 加密 + 未解锁时无密钥、无数据库句柄；任何界面被 `VaultGate` 拦截；退到后台立即锁定并清除解密残留 |
| 4. 阅读书源加入书架后，正文保存到数据目录并同样加密 | 加入书架自动缓存全本；章节正文以 AES-256-GCM 分块加密写入 `vault/books/<hash>/` |

## 二、目录结构（`/data/data/<包名>/files/`）

```
files/
├── novelVault/
│   ├── vault.info                 ← 唯一的未加密文件（不含书名/封面/正文）
│   └── vault/                     ← 加密文件夹
│       ├── library.db             ← SQLCipher 加密数据库（书架、书源、阅读进度、替换规则…）
│       ├── library.db-wal / -shm
│       ├── books/<id>.bin         ← 本地导入书籍正文（AES-256-GCM）
│       ├── books/<id>/<章节>.nb   ← 书源章节正文（AES-256-GCM）
│       ├── covers/<id>.bin        ← 本地书封面（AES-256-GCM）
│       └── legacy/*.bin           ← 首次启用时对旧明文数据做的加密归档
└── .vaultwork/                    ← 阅读时解密出的临时工作副本，锁定时整目录删除
```

## 三、加解密设计

- 口令派生：PBKDF2-HMAC-SHA256，200000 次迭代，16 字节盐
- 主密钥：随机 256 位，用口令派生密钥以 AES-256-GCM 包裹后存进 `vault.info`
- 数据库口令：`SHA256(主密钥 || "legado-db-key-v1")`，与用户密码解耦，改密码不需要重新加密数据库
- 文件加密：AES-256-GCM，64KB 分块，每块独立 nonce + 块序号 AAD，可防重排/截断；文件头 `LVF\1` + 8 字节随机前缀
- 改密码：只重新包裹主密钥（`VaultSession.changePassword`）

## 四、锁定策略

| 场景 | 行为 |
| --- | --- |
| 冷启动 | 必须输入密码；密码错误有递增延时，无法进入任何界面 |
| 退到后台（Home/切走） | 立即标记锁定，关闭数据库、抹掉内存密钥、删除 `.vaultwork` 与缓存、停止 Web 服务 |
| 从最近任务划掉 | 同上（`LifecycleHelp` 生命周期归零时触发） |
| 未解锁时打开任意 Activity | `BaseActivity` 在 `onCreate/onStart` 拦截并跳回解锁页，界面先置为不可见防闪现 |
| 忘记密码 | 解锁页提供「清空全部加密数据」 |

## 五、涉及文件

新增：

- `vault/VaultCrypto.kt` — 加解密原语（PBKDF2 / AES-GCM 分块流）
- `vault/VaultStorage.kt` — 保险库目录布局与文件读写
- `vault/VaultSession.kt` — 密码校验、会话密钥、锁定、改密码
- `vault/VaultContext.kt` — 把数据库路径重定向进保险库
- `vault/VaultGate.kt` — 界面守卫与解锁后维护
- `ui/vault/UnlockActivity.kt` + `res/layout/activity_unlock.xml` — 解锁/设置密码界面

修改（均为最小侵入）：

- `App.kt` — 需要数据库的初始化延后到解锁之后
- `data/AppDatabase.kt` — `appDb` 改为 `AppDbHolder`，SQLCipher 打开数据库；未解锁抛 `VaultLockedException`
- `base/BaseActivity.kt` — 未解锁拦截
- `help/LifecycleHelp.kt` — 前后台计数，退后台锁定
- `AndroidManifest.xml` — 启动入口换成 `UnlockActivity`
- `model/localBook/LocalBook.kt` — 本地书/压缩包/在线文件/WEBDAV 全部直接加密入库；封面改到工作目录
- `help/book/BookHelp.kt` — 章节正文缓存全部走保险库（含 `nr` 标记文件）
- `help/book/BookExtensions.kt` — `getLocalUri` 支持 `vault://`
- `ui/book/info/BookInfoViewModel.kt` — 加入书架后自动缓存全本（触发加密落盘）
- `api/controller/BookController.kt`、`model/remote/RemoteBookWebDav.kt` — 上传/下载改走保险库
- `app/build.gradle`、`gradle/libs.versions.toml` — 引入 `net.zetetic:sqlcipher-android`

## 六、编译

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleAppDebug
# 产物: app/build/outputs/apk/app/debug/legado_app_*.apk
```

## 七、已实测验证（Android 15 模拟器）

1. 首次启动要求设置密码 → 生成 `vault.info` + `vault/library.db`
2. 数据库文件头非 `SQLite format 3`，`strings` 搜不到书名
3. 导入 txt / epub → 生成 `books/<id>.bin`，正文特征串在密文中搜不到（0 命中）
4. 书源搜索 → 加入书架 → 章节正文自动缓存为 `books/<hash>/00000-*.nb`，密文中搜不到正文特征串
5. 阅读界面能正常解密显示正文
6. 退到后台后 `.vaultwork` 内明文副本被删除
7. 强杀/退后台后重进必须重新输入密码，书架不可见
8. 升级安装（覆盖安装）后密码与书架数据保留