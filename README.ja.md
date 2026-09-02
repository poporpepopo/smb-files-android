# SMB Documents Provider

[English](README.md) | **日本語**

SMB 共有を Android の Storage Access Framework に公開し、標準のファイルブラウザと
あらゆるアプリのファイル選択画面から場所として見えるようにします。独自の閲覧 UI は
持ちません — それがこの設計の要点です。画面は認証情報を入力する1枚だけです。

ASUS TUF-BE9400 ルーターに挿した USB メモリを共有し、Pixel 10 (Android 17) から
検証しています。

## ビルド

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` は `~/Library/Android/sdk` を指します。

## 構成

| ファイル | 役割 |
|---|---|
| `SmbDocumentsProvider.kt` | 本体。一覧・読み・書き・作成・削除・リネーム・サムネイル |
| `SmbConnection.kt` | `DiskShare` を1本保持し、ルーターに切られたら張り直す |
| `SmbSettings.kt` | 認証情報を `EncryptedSharedPreferences` に保存 |
| `SettingsActivity.kt` | ホスト / 共有名 / ユーザー / パスワードと接続テスト |

ドキュメント ID は共有内のパスで、先頭スラッシュに正規化しています。`/` が共有の
ルート、`/Photos/a.png` がファイルです。バックスラッシュ区切りへの変換は
`smbPath()` の1箇所だけで行います。

## 間違えやすい4点

いずれも実機で踏んだ実際のバグです。コードがこの形になっている理由でもあります。

**Binder は呼び出し元の StrictMode ポリシーを持ち込む。** DocumentsUI は
`detectNetwork()` 付きで動いており、そのポリシーがプロバイダの binder スレッドに
引き継がれます。結果、メインスレッドではないのに、受け取ったスレッドで SMB I/O を
行うと `NetworkOnMainThreadException` が飛びます。そのため SMB 呼び出しはすべて、
このアプリが所有する（自前でポリシーを設定した）エグゼキュータのスレッドに投げます。

**短い読み取りは EOF として扱われる。** フレームワークはプロキシファイルディスクリプタに
128 KB を要求しますが、SMB の1回の読み取りはネゴシエートされた上限（ここでは 64 KB）
までしか返しません。その短い戻り値でファイルが終わったことになります。`onRead` は
要求範囲を埋めるまでループします。修正前の症状は「全ファイルが先頭 64 KB に切り詰め
られる」で、エラーではなく画像が半分だけ描画される形で現れるため厄介です。

**`setNotificationUri` は省略できない。** `queryChildDocuments` が返すカーソルに
これを設定しないと、DocumentsUI はオブザーバを登録せず、`notifyChange()` は誰にも
届きません。一覧はリネームや削除で無効になった古いドキュメント ID を返し続け、
*次の*操作が存在しないパスに対して失敗します。

**`FLAG_SUPPORTS_THUMBNAIL` を立てたら `openDocumentThumbnail` の実装義務が生じる。**
フラグだけを宣言すると、画像の行ごとに例外が飛びます。

## 共有への実際の入り方

ドキュメントから受ける印象と実態が違うため、Pixel 10 で実測した結果です。

| 経路 | 可否 |
|---|---|
| 他アプリのファイル選択画面（添付・開く・保存） | ○ ドロワーに `//host/share` として常駐 |
| `OpenShareActivity` — アプリアイコン、またはその長押しショートカット「共有」 | ○ 共有のルートで DocumentsUI が開く |
| Files by Google → その他のストレージ → 本アプリ | × インラインで開かず、本アプリを起動するだけ |
| DocumentsUI 単体 | × Pixel にはランチャーアイコンが無く、ホーム画面から起動できない |

下2つが `OpenShareActivity` の存在理由です。これが無いと、共有は他アプリのファイル
選択画面の中からしか辿り着けず、NAS を見に行く用途には不便です。

## 意図的な制限

- 読み取りは `StorageManager.openProxyFileDescriptor` でストリーミングします。大きな
  動画を開いてもキャッシュに全体をコピーしません。書き込みはキャッシュファイルに
  溜めてクローズ時にアップロードします。SMB は書き込み可能な fd を渡せず、また
  中断したアップロードで共有側のファイルを切り詰めないためです。
- サムネイルは画像全体をメモリに載せる必要があるため、32 MB を超えるファイルは
  汎用アイコンのままにします。
- 同時に扱える共有は1つです。
- 速度の上限はルーター側であり、このアプリではありません。
