package com.example.ae2uelthings.disk;

import net.minecraftforge.fml.common.Loader;

/**
 * 他MOD検出用のユーティリティ。
 *
 * <p>mod ID は実際のログ(latest.log の "| LCHIJA | ... |" 一覧)で確認済み:
 * NAE2 (Neeve's AE2: Extended Life Additions) = {@code nae2}</p>
 *
 * <p>NAE2は独自に256k/1024k/4096k/16384kの「Crafting Storage」を持っている
 * (実際のモデルパス nae2:block/crafting/storage_crafting_16384k 等で確認済み)。
 * これに揃える形で、NAE2検出時は ae2uelthings 側の拡張ティア(DiskTier.TIER_256K〜
 * TIER_16384K)を追加登録する。これは「NAE2側から認識される」という意味の対応ではなく、
 * 「NAE2が入っている=大容量志向の環境」という判定materialとして使い、
 * ae2uelthings自身の上位ティアを追加で解放する、という位置付け。</p>
 *
 * (理論上の最大ティア DiskTier.TIER_MAX は特定MODの検出とは無関係に常に登録されるため、
 * ここでの判定は不要。)
 */
public final class ModCompat {

    public static final String NAE2_MODID = "nae2";

    private static Boolean nae2LoadedCache;

    private ModCompat() {
    }

    /**
     * NAE2が読み込まれているか。
     * Loader.isModLoaded()はFMLの初期化がある程度進んだ後でないと正確でない場合があるため、
     * RegistryEvent.Register(Item)以降(preInit完了後)での呼び出しを想定している。
     */
    public static boolean isNae2Loaded() {
        if (nae2LoadedCache == null) {
            nae2LoadedCache = Loader.isModLoaded(NAE2_MODID);
        }
        return nae2LoadedCache;
    }
}