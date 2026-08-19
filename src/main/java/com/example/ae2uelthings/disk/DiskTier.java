package com.example.ae2uelthings.disk;

/**
 * DISK (Deep Item Storage disK) セルの4段階の容量ティアを定義する。
 *
 * <p>容量モデル: 「アイテム1個 = 1byte」を採用しているため、{@link #getTotalBytes()}
 * はそのままそのティアが理論上格納できる最大アイテム数(全タイプ合算)を表す。</p>
 *
 * <p>AE2 Things オリジナル実装の「1kあたり24byteオーバーヘッド」という仕様を踏襲し、
 * ネットワークに公開する実質使用可能容量の計算に反映している({@link #getUsableBytes()}）。</p>
 *
 * <p><b>要ローカル検証:</b> AE2 rv6 の {@code IItemStorageChannel#getUnitsPerByte()} は
 * デフォルトでは 8(=1byteで8アイテム)であり、これはチャンネル側の固定値でセル側からは
 * 変更できない。したがって「1 item = 1 byte」を厳密に実現するには、AE2UELの実際の
 * unitsPerByte値をIDE上で確認し、必要であれば独自 IStorageChannel の実装を検討すること。
 * ここでは "getBytes() = 格納したいアイテム数" という前提でひとまず実装している。</p>
 *
 * 修正メモ: 以前ここに DiskStorageManager をstaticネストクラスとして同居させていたが、
 * 他クラスから参照する際の修飾漏れ(コンパイルエラーの原因になった)を避けるため、
 * com.example.ae2uelthings.disk.storage.DiskStorageManager として独立させた。
 * DiskTierはあくまで容量ティアの定義のみを担当する。
 */
public enum DiskTier {

    TIER_1K("1k", 1_024, 24, 35),
    TIER_4K("4k", 4_096, 24 * 4, 36),
    TIER_16K("16k", 16_384, 24 * 16, 37),
    TIER_64K("64k", 65_536, 24 * 64, 38);

    private final String suffix;
    private final int totalBytes;
    private final int overheadBytes;
    /** appliedenergistics2:material のうち、対応するストレージコンポーネントのmetadata値 */
    private final int componentMeta;

    DiskTier(String suffix, int totalBytes, int overheadBytes, int componentMeta) {
        this.suffix = suffix;
        this.totalBytes = totalBytes;
        this.overheadBytes = overheadBytes;
        this.componentMeta = componentMeta;
    }

    /** appliedenergistics2:material 側の対応コンポーネントのmetadata値 */
    public int getComponentMeta() {
        return componentMeta;
    }

    /** アイテム登録名などに使う接尾辞 (例: "1k", "4k") */
    public String getSuffix() {
        return suffix;
    }

    /** オーバーヘッド適用前の生の容量。1item=1byteモデルでは理論最大格納数と一致する。 */
    public int getTotalBytes() {
        return totalBytes;
    }

    /** ティアごとに固定でかかるオーバーヘッド (AE2 Thingsの「24byte/1k」を踏襲) */
    public int getOverheadBytes() {
        return overheadBytes;
    }

    /** オーバーヘッド差し引き後、実際にアイテム格納に使える容量 */
    public int getUsableBytes() {
        return Math.max(0, totalBytes - overheadBytes);
    }

    /** アイテム登録名 (例: "disk_cell_1k") */
    public String getItemId() {
        return "disk_cell_" + suffix;
    }
}