package com.example.ae2uelthings.disk;

/**
 * DISK (Deep Item Storage disK) セルの容量ティアを定義する。
 *
 * <p>容量モデル: 「アイテム1個 = 1byte」を採用しているため、{@link #getUsableBytes()}
 * はそのままそのティアが格納できる最大アイテム数(全タイプ合算)を表す。</p>
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
 *
 * <p><b>拡張ティア(TIER_256K〜TIER_16384K)について:</b> NAE2が独自に持っている
 * 「Crafting Storage」の容量ラインナップ(256k/1024k/4096k/16384k。実際のモデルパス
 * nae2:block/crafting/storage_crafting_16384k 等で確認済み)に合わせて、
 * 既存の1k→4k→16k→64kと同じ×4刻みで先の4段階を定義した。NAE2検出時
 * ({@link ModCompat#isNae2Loaded()}) にのみ登録される。クラフト素材は
 * {@code assets/ae2uelthings/recipes/disk_cell_*.json} の実際のレシピに合わせて
 * {@code nae2:material}(data 1〜4, 液体版は5〜8)を使う({@link #getComponentItemId()}/
 * {@link #getFluidComponentMeta()} 等参照)。</p>
 *
 * <p><b>TIER_MAXについて:</b>
 * (2,147,483,647 = {@link Integer#MAX_VALUE})まで対応していることを参考にした
 * ティア。特定MODの検出とは無関係に常に登録される。{@link #getUsableBytes()} が
 * 最終的にAE2側へは {@code int} で渡る({@link com.example.ae2uelthings.api.IDiskCellDefinition#getBytes})
 * ため、これ以上大きい値は原理的に扱えない。既存の×4刻みをそのまま延長すると
 * 4194304k(4,294,967,296)でintの上限を超えて桁あふれするため、TIER_MAXだけは
 * 規則から外し、「usableBytes = Integer.MAX_VALUE ぴったり」になるよう直接定義している。
 * 対応するクラフトレシピが存在しないため、componentMeta/fluidComponentMetaは
 * どちらも-1(未定義)のまま。</p>
 */
public enum DiskTier {

    // suffix, totalBytes, componentMeta(アイテム版), fluidComponentMeta(液体版)
    TIER_1K("1k", 1_024, 35, 54),
    TIER_4K("4k", 4_096, 36, 55),
    TIER_16K("16k", 16_384, 37, 56),
    TIER_64K("64k", 65_536, 38, 57),

    // ---- 拡張ティア(NAE2検出時のみ登録。componentは appliedenergistics2:material ではなく
    //      nae2:material 側(disk_cell_*_alt.jsonのレシピと一致させる)) ----
    TIER_256K("256k", 262_144, 1, 5, true),
    TIER_1024K("1024k", 1_048_576, 2, 6, true),
    TIER_4096K("4096k", 4_194_304, 3, 7, true),
    TIER_16384K("16384k", 16_777_216, 4, 8, true),

    // ---- 理論上の最大ティア(特定MODの検出とは無関係に常に登録。CrazyAEのint上限対応を参考にした) ----
    TIER_MAX("max", Integer.MAX_VALUE, -1, -1);

    /** 標準ティア(1k〜64k)の分解コンポーネントが属するアイテムID。 */
    private static final String STANDARD_COMPONENT_ITEM = "appliedenergistics2:material";
    /** 拡張ティア(256k〜16384k)の分解コンポーネントが属するアイテムID(NAE2側)。 */
    private static final String EXTENDED_COMPONENT_ITEM = "nae2:material";

    private final String suffix;
    private final int totalBytes;
    /** アイテム版DISKの分解で返すコンポーネントのmetadata値。未定義なら-1。 */
    private final int componentMeta;
    /** 液体版DISKの分解で返すコンポーネントのmetadata値。未定義なら-1。 */
    private final int fluidComponentMeta;
    /** コンポーネントのアイテムIDが nae2:material 側(拡張ティア)かどうか。 */
    private final boolean nae2Material;

    DiskTier(String suffix, int totalBytes, int componentMeta, int fluidComponentMeta) {
        this(suffix, totalBytes, componentMeta, fluidComponentMeta, false);
    }

    DiskTier(String suffix, int totalBytes, int componentMeta, int fluidComponentMeta, boolean nae2Material) {
        this.suffix = suffix;
        this.totalBytes = totalBytes;
        this.componentMeta = componentMeta;
        this.fluidComponentMeta = fluidComponentMeta;
        this.nae2Material = nae2Material;
    }

    /**
     * 分解コンポーネントの登録名(domain:path)。アイテム版・液体版で共通
     * (差はmetadata値のみ。disk_cell_*.json / disk_cell_fluid_*.jsonのレシピ参照)。
     */
    public String getComponentItemId() {
        return nae2Material ? EXTENDED_COMPONENT_ITEM : STANDARD_COMPONENT_ITEM;
    }

    /** アイテム版DISKの分解で返すコンポーネントのmetadata値。無ければ-1。 */
    public int getComponentMeta() {
        return componentMeta;
    }

    /** 液体版DISKの分解で返すコンポーネントのmetadata値。無ければ-1。 */
    public int getFluidComponentMeta() {
        return fluidComponentMeta;
    }

    /** アイテム版DISKのクラフト/分解用コンポーネントが定義されているか。 */
    public boolean hasComponent() {
        return componentMeta >= 0;
    }

    /** 液体版DISKのクラフト/分解用コンポーネントが定義されているか。 */
    public boolean hasFluidComponent() {
        return fluidComponentMeta >= 0;
    }

    /** アイテム登録名などに使う接尾辞 (例: "1k", "4k") */
    public String getSuffix() {
        return suffix;
    }

    /** このティアの容量。1item=1byteモデルでは理論最大格納数と一致する。 */
    public int getTotalBytes() {
        return totalBytes;
    }

    /**
     * 実際にアイテム格納に使える容量。
     * 参考元(AE2 MEGA Things)に合わせ、オーバーヘッドは無く totalBytes とそのまま一致する。
     */
    public int getUsableBytes() {
        return totalBytes;
    }

    /** アイテム登録名 (例: "disk_cell_1k") */
    public String getItemId() {
        return "disk_cell_" + suffix;
    }
}