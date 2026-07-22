import React, { useEffect, useState, useRef, useCallback } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  AppState,
  KeyboardAvoidingView,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  Dimensions,
} from 'react-native';

const { XHermesModule } = NativeModules;
const { width: SCREEN_WIDTH } = Dimensions.get('window');

// ── MD3 Material You (Dark) Palette ──────────────────────────────────────────
const C = {
  // Backgrounds
  bg:               '#0F0D13',
  surfaceLowest:    '#1D1B20',
  surface:          '#1D1B20',
  surfaceContainer: '#211F26',
  surfaceHigh:      '#2B2930',
  surfaceBright:    '#3B383E',
  // Primary tonal
  primary:          '#D0BCFF',
  primaryDim:       '#B69DF8',
  onPrimary:        '#381E72',
  primaryContainer: '#4F378B',
  onPrimaryContainer:'#EADDFF',
  // Secondary
  secondary:        '#CCC2DC',
  secondaryContainer:'#4A4458',
  onSecondaryContainer:'#E8DEF8',
  // Tertiary
  tertiary:         '#EFB8C8',
  tertiaryContainer:'#633B48',
  // Text
  onBg:             '#E6E0E9',
  onSurface:        '#E6E0E9',
  onSurfaceVar:     '#CAC4D0',
  // Utility
  outline:          '#938F99',
  outlineVar:       '#49454F',
  // Semantic
  success:          '#A8DAB5',
  successContainer: '#1B5E20',
  error:            '#F2B8B5',
  errorContainer:   '#8C1D18',
  onError:          '#601410',
  warning:          '#FFD599',
  // Elevation overlay
  scrim:            '#000000',
};

// ── Interfaces ───────────────────────────────────────────────────────────────
interface SavedScript {
  id: string;
  name: string;
  code: string;
  type: 'pre' | 'post';
}

interface PackageConfig {
  id: string;
  targetPackage: string;
  enabled: boolean;
  preScriptId: string | null;
  postScriptId: string | null;
  preScriptEnabled: boolean;
  postScriptEnabled: boolean;
  blockOriginalBundle: boolean;
  injectWebView: boolean;
  enableWebViewDebugging: boolean;
  webViewUrl: string;
  hollowProcess: boolean;
}

interface InstalledApp {
  label: string;
  packageName: string;
  isSystem: boolean;
  isReactNative: boolean;
}

// ── Default Scripts ──────────────────────────────────────────────────────────
const DEFAULT_SCRIPTS: SavedScript[] = [
  {
    id: 'default-pre-empty',
    name: 'Boş Öncül Şablon',
    code: '// Buraya orijinal bundle yüklenmeden önce çalışacak kodları yazın\n',
    type: 'pre',
  },
  {
    id: 'default-post-empty',
    name: 'Boş Ardıl Şablon',
    code: '// Buraya orijinal bundle yüklendikten sonra çalışacak kodları yazın\n',
    type: 'post',
  },
  {
    id: 'default-pre-logger',
    name: 'Konsol Log Yakala',
    code: `// console.log, info ve error çağrılarını yakalayıp özelleştirme şablonu
(function() {
  const originalLog = console.log;
  console.log = function(...args) {
    originalLog("[XHermes-Pre][Log]", ...args);
  };
  
  const originalError = console.error;
  console.error = function(...args) {
    originalError("[XHermes-Pre][Error]", ...args);
  };
  
  originalLog("[+] XHermes: Konsol logları dinleniyor.");
})();
`,
    type: 'pre',
  },
  {
    id: 'default-pre-fetch',
    name: 'Fetch Spy (API İzleyici)',
    code: `// Ağ isteklerini (fetch) konsola yazdıran şablon
(function() {
  const originalFetch = global.fetch;
  if (originalFetch) {
    global.fetch = function(input, init) {
      const url = typeof input === 'string' ? input : (input && input.url) ? input.url : 'Bilinmeyen URL';
      const method = (init && init.method) ? init.method : 'GET';
      console.log(\`[XHermes-Pre][Fetch] İstek: [\${method}] \${url}\`);
      
      return originalFetch.apply(this, arguments)
        .then(response => {
          console.log(\`[XHermes-Pre][Fetch] Yanıt: [\${response.status}] \${url}\`);
          return response;
        })
        .catch(err => {
          console.error(\`[XHermes-Pre][Fetch] Hata: \${url} - \${err.message}\`);
          throw err;
        });
    };
    console.log("[+] XHermes: Fetch API dinleniyor.");
  }
})();
`,
    type: 'pre',
  },
  {
    id: 'default-post-vars',
    name: 'Global Bayrak Tanımla',
    code: `// Orijinal kod çalıştıktan sonra global değişkenler atama şablonu
(function() {
  global.__XHERMES_INJECTED__ = true;
  global.__XHERMES_VERSION__ = "1.0.0";
  console.log("[+] XHermes: Ardıl modül yüklendi ve global değişkenler tanımlandı.");
})();
`,
    type: 'post',
  },
];

// ── Reusable Components ──────────────────────────────────────────────────────

// Icon characters (emoji-free unicode approach)
const ICONS = {
  home: '⌂',
  apps: '◈',
  scripts: '❮❯',
  settings: '⚙',
  active: '●',
  add: '+',
  remove: '✕',
  chevron: '›',
  check: '✓',
  warning: '⚠',
  code: '</>',
  webview: '⊞',
  shield: '◆',
};

const SectionHeader = ({ title, subtitle }: { title: string; subtitle?: string }) => (
  <View style={s.sectionHeader}>
    <Text style={s.sectionTitle}>{title}</Text>
    {subtitle && <Text style={s.sectionSubtitle}>{subtitle}</Text>}
  </View>
);

const SettingRow = ({
  title,
  description,
  value,
  onToggle,
  disabled = false,
}: {
  title: string;
  description?: string;
  value: boolean;
  onToggle: (val: boolean) => void;
  disabled?: boolean;
}) => (
  <TouchableOpacity
    style={[s.settingRow, disabled && { opacity: 0.4 }]}
    activeOpacity={0.65}
    disabled={disabled}
    onPress={() => onToggle(!value)}
  >
    <View style={s.settingTextWrap}>
      <Text style={s.settingTitle}>{title}</Text>
      {description && <Text style={s.settingDesc}>{description}</Text>}
    </View>
    <Switch
      value={value}
      onValueChange={onToggle}
      disabled={disabled}
      trackColor={{ false: C.outlineVar, true: C.primaryContainer }}
      thumbColor={value && !disabled ? C.primary : C.outline}
    />
  </TouchableOpacity>
);

const Pill = ({
  label,
  selected,
  onPress,
  disabled,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  disabled?: boolean;
}) => (
  <TouchableOpacity
    style={[s.pill, selected && s.pillSelected]}
    onPress={onPress}
    disabled={disabled}
    activeOpacity={0.7}
  >
    <Text style={[s.pillText, selected && s.pillTextSelected]}>{label}</Text>
  </TouchableOpacity>
);

const Divider = () => <View style={s.divider} />;

// ── Main App ─────────────────────────────────────────────────────────────────
export default function App() {
  const [isActive, setIsActive] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(true);
  const [currentTab, setCurrentTab] = useState<'home' | 'apps' | 'scripts' | 'settings'>('home');

  // Data
  const [configs, setConfigs] = useState<PackageConfig[]>([]);
  const [selectedConfigId, setSelectedConfigId] = useState<string | null>(null);
  const [scripts, setScripts] = useState<SavedScript[]>(DEFAULT_SCRIPTS);
  const [selectedScriptId, setSelectedScriptId] = useState<string | null>(null);

  // Input states
  const [newPackageInput, setNewPackageInput] = useState('');
  const [newScriptName, setNewScriptName] = useState('');
  const [newScriptCode, setNewScriptCode] = useState('');
  const [newScriptType, setNewScriptType] = useState<'pre' | 'post'>('pre');

  // Installed apps
  const [installedApps, setInstalledApps] = useState<InstalledApp[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterRNOnly, setFilterRNOnly] = useState(true);

  // Accessibility offset
  const [uiOffset, setUiOffset] = useState(0);

// Adaptive top offset based on status bar and screen dimensions
const ADAPTIVE_TOP_OFFSET = Math.max(60, Platform.OS === 'android' ? (StatusBar.currentHeight || 0) + 20 : 44);

  // Hooked packages from framework
  const [hookedPackages, setHookedPackages] = useState<string[]>([]);

  // Toast
  const toastOpacity = useRef(new Animated.Value(0)).current;
  const [toastMessage, setToastMessage] = useState('');

  // Status pulse animation
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    loadSettings();
    checkModuleActive();
    fetchInstalledApps();
    fetchHookedPackages();

    // 1. AppState listener for Foreground/Background transitions (Açılış - Kapanış)
    const subscription = AppState.addEventListener('change', (nextAppState) => {
      if (nextAppState === 'active') {
        checkModuleActive();
        fetchHookedPackages();
      }
    });

    // 2. Periodic background refresh every 5 seconds while UI is open
    const interval = setInterval(() => {
      checkModuleActive();
      fetchHookedPackages();
    }, 5000);

    return () => {
      subscription.remove();
      clearInterval(interval);
    };
  }, []);

  const handleResetHookStatus = async (silent = false) => {
    try {
      if (XHermesModule?.clearHookedStatus) {
        await XHermesModule.clearHookedStatus();
        setHookedPackages([]);
        await checkModuleActive();
        await fetchHookedPackages();
        if (!silent) {
          showToast('Kanca durumları temizlendi. Uygulamalar yeniden açıldığında güncellenecek.');
        }
      }
    } catch (e) {
      console.warn('Failed to clear hooked status:', e);
    }
  };

  useEffect(() => {
    if (isActive) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 0.4, duration: 1200, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 1200, useNativeDriver: true }),
        ])
      ).start();
    }
  }, [isActive]);

  // ── Data Methods ───────────────────────────────────────────────────────────
  const fetchHookedPackages = async () => {
    try {
      if (XHermesModule?.getHookedPackages) {
        const json = await XHermesModule.getHookedPackages();
        const parsed: string[] = JSON.parse(json);
        setHookedPackages(parsed);
      }
    } catch (e) {
      console.warn('Failed to fetch hooked packages:', e);
    }
  };

  const fetchInstalledApps = async () => {
    try {
      if (XHermesModule?.getInstalledApps) {
        const json = await XHermesModule.getInstalledApps();
        const parsed: InstalledApp[] = JSON.parse(json);
        parsed.sort((a, b) => {
          if (a.isReactNative && !b.isReactNative) return -1;
          if (!a.isReactNative && b.isReactNative) return 1;
          return a.label.localeCompare(b.label);
        });
        setInstalledApps(parsed);
      }
    } catch (e) {
      console.warn('Failed to fetch installed apps:', e);
    }
  };

  const showToast = useCallback((message: string) => {
    setToastMessage(message);
    Animated.sequence([
      Animated.timing(toastOpacity, { toValue: 1, duration: 150, useNativeDriver: true }),
      Animated.delay(1800),
      Animated.timing(toastOpacity, { toValue: 0, duration: 300, useNativeDriver: true }),
    ]).start();
  }, [toastOpacity]);

  const loadSettings = async () => {
    try {
      if (XHermesModule) {
        const json = await XHermesModule.getConfigs();
        if (json?.trim()) {
          const parsed = JSON.parse(json);
          const metadata = parsed.find((item: any) => item.targetPackage === '__xhermes_metadata__');
          if (metadata) {
            const loadedScripts = JSON.parse(metadata.preScript);
            const loadedConfigs = JSON.parse(metadata.postScript);
            setScripts(loadedScripts);
            setConfigs(loadedConfigs);
            if (loadedConfigs.length > 0) setSelectedConfigId(loadedConfigs[0].id);
          } else {
            reconstructLegacyConfig(parsed);
          }
        }
      }
    } catch (e) {
      console.error(e);
      Alert.alert('Hata', 'Ayarlar yüklenemedi.');
    } finally {
      setLoading(false);
    }
  };

  const reconstructLegacyConfig = (parsedConfigs: any[]) => {
    const newScriptsList: SavedScript[] = [...DEFAULT_SCRIPTS];
    const newConfigsList: PackageConfig[] = [];
    parsedConfigs.forEach((c: any) => {
      if (c.targetPackage === '__xhermes_metadata__') return;
      const preId = `script-pre-${c.id}`;
      const postId = `script-post-${c.id}`;
      newScriptsList.push({ id: preId, name: `${c.targetPackage} Öncül`, code: c.preScript || '', type: 'pre' });
      newScriptsList.push({ id: postId, name: `${c.targetPackage} Ardıl`, code: c.postScript || '', type: 'post' });
      newConfigsList.push({
        id: c.id, targetPackage: c.targetPackage, enabled: c.enabled,
        preScriptId: preId, postScriptId: postId,
        preScriptEnabled: c.preScriptEnabled, postScriptEnabled: c.postScriptEnabled,
        blockOriginalBundle: c.blockOriginalBundle || false,
        injectWebView: c.injectWebView || false,
        enableWebViewDebugging: c.enableWebViewDebugging || false,
        webViewUrl: c.webViewUrl || '',
        hollowProcess: c.hollowProcess || false,
      });
    });
    setScripts(newScriptsList);
    setConfigs(newConfigsList);
    if (newConfigsList.length > 0) setSelectedConfigId(newConfigsList[0].id);
  };

  const checkModuleActive = async () => {
    try {
      if (XHermesModule) {
        const active = await XHermesModule.isModuleActive();
        setIsActive(active);
      }
    } catch (e) {
      setIsActive(false);
    }
  };

  const saveStateToNative = async (currentConfigs: PackageConfig[], currentScripts: SavedScript[]) => {
    setLoading(true);
    try {
      if (XHermesModule) {
        const resolvedConfigs = currentConfigs.map(c => {
          const preScript = currentScripts.find(sc => sc.id === c.preScriptId)?.code || '';
          const postScript = currentScripts.find(sc => sc.id === c.postScriptId)?.code || '';
          return {
            id: c.id, targetPackage: c.targetPackage, enabled: c.enabled,
            preScript, postScript, preScriptEnabled: c.preScriptEnabled,
            postScriptEnabled: c.postScriptEnabled, blockOriginalBundle: c.blockOriginalBundle,
            injectWebView: c.injectWebView, enableWebViewDebugging: c.enableWebViewDebugging,
            webViewUrl: c.webViewUrl, hollowProcess: c.hollowProcess || false,
          };
        });
        const metadataEntry = {
          id: '__metadata__', targetPackage: '__xhermes_metadata__', enabled: false,
          preScript: JSON.stringify(currentScripts), postScript: JSON.stringify(currentConfigs),
          preScriptEnabled: false, postScriptEnabled: false, blockOriginalBundle: false,
          injectWebView: false, enableWebViewDebugging: false, webViewUrl: '',
        };
        await XHermesModule.saveConfigs(JSON.stringify([...resolvedConfigs, metadataEntry]));
        await checkModuleActive();
        await fetchHookedPackages();
        showToast('Değişiklikler kaydedildi');
      }
    } catch (e) {
      Alert.alert('Hata', 'Değişiklikler kaydedilemedi.');
    } finally {
      setLoading(false);
    }
  };

  // ── App Management ─────────────────────────────────────────────────────────
  const handleAddNewPackage = (packageNameStr: string) => {
    const pkg = packageNameStr.trim();
    if (!pkg) { Alert.alert('Uyarı', 'Lütfen geçerli bir paket adı girin.'); return; }
    if (configs.some(c => c.targetPackage.toLowerCase() === pkg.toLowerCase())) {
      Alert.alert('Uyarı', 'Bu paket zaten eklenmiş.'); return;
    }
    const newId = Math.random().toString(36).substring(2, 9);
    const defaultPre = scripts.find(sc => sc.type === 'pre')?.id || null;
    const defaultPost = scripts.find(sc => sc.type === 'post')?.id || null;
    const newConfig: PackageConfig = {
      id: newId, targetPackage: pkg, enabled: true,
      preScriptId: defaultPre, postScriptId: defaultPost,
      preScriptEnabled: true, postScriptEnabled: true,
      blockOriginalBundle: false, injectWebView: false,
      enableWebViewDebugging: false, webViewUrl: '',
      hollowProcess: false,
    };
    const newConfigs = [...configs, newConfig];
    setConfigs(newConfigs);
    setSelectedConfigId(newId);
    setNewPackageInput('');
    saveStateToNative(newConfigs, scripts);
  };

  const handleDeletePackage = (id: string) => {
    Alert.alert('Uygulamayı Kaldır', 'Bu uygulamanın tüm yapılandırması silinecek. Devam edilsin mi?', [
      { text: 'İptal', style: 'cancel' },
      { text: 'Kaldır', style: 'destructive', onPress: () => {
        const filtered = configs.filter(c => c.id !== id);
        setConfigs(filtered);
        if (selectedConfigId === id) setSelectedConfigId(filtered[0]?.id || null);
        saveStateToNative(filtered, scripts);
      }},
    ]);
  };

  const updateConfigField = (field: keyof PackageConfig, value: any) => {
    if (!selectedConfigId) return;
    const updated = configs.map(c => {
      if (c.id === selectedConfigId) {
        if (field === 'injectWebView' && value === true)
          return { ...c, injectWebView: true, preScriptEnabled: false, postScriptEnabled: false };
        if (field === 'blockOriginalBundle' && value === false)
          return { ...c, blockOriginalBundle: false, injectWebView: false, enableWebViewDebugging: false };
        return { ...c, [field]: value };
      }
      return c;
    });
    setConfigs(updated);
    saveStateToNative(updated, scripts);
  };

  // ── Script Management ──────────────────────────────────────────────────────
  const handleAddNewScript = () => {
    const name = newScriptName.trim();
    if (!name) { Alert.alert('Hata', 'Script adı boş olamaz.'); return; }
    const newId = `script-custom-${Math.random().toString(36).substring(2, 9)}`;
    const newScript: SavedScript = { id: newId, name, code: newScriptCode.trim() || '// Yeni kod buraya\n', type: newScriptType };
    const updatedScripts = [...scripts, newScript];
    setScripts(updatedScripts);
    setSelectedScriptId(newId);
    setNewScriptName('');
    setNewScriptCode('');
    saveStateToNative(configs, updatedScripts);
  };

  const handleUpdateScript = (id: string, updatedCode: string, updatedName?: string) => {
    const updated = scripts.map(sc => {
      if (sc.id === id) return { ...sc, code: updatedCode, name: updatedName?.trim() || sc.name };
      return sc;
    });
    setScripts(updated);
    saveStateToNative(configs, updated);
  };

  const handleDeleteScript = (id: string) => {
    if (id.startsWith('default-')) { Alert.alert('Hata', 'Varsayılan şablonlar silinemez.'); return; }
    Alert.alert('Scripti Sil', 'Bu script kalıcı olarak silinecek. Devam edilsin mi?', [
      { text: 'İptal', style: 'cancel' },
      { text: 'Sil', style: 'destructive', onPress: () => {
        const filteredScripts = scripts.filter(sc => sc.id !== id);
        const updatedConfigs = configs.map(c => ({
          ...c,
          preScriptId: c.preScriptId === id ? null : c.preScriptId,
          postScriptId: c.postScriptId === id ? null : c.postScriptId,
        }));
        setScripts(filteredScripts);
        setConfigs(updatedConfigs);
        if (selectedScriptId === id) setSelectedScriptId(filteredScripts[0]?.id || null);
        saveStateToNative(updatedConfigs, filteredScripts);
      }},
    ]);
  };

  const resetAllData = () => {
    Alert.alert('Fabrika Sıfırlama', 'Tüm uygulamalar ve scriptler silinecek. Bu işlem geri alınamaz.', [
      { text: 'İptal', style: 'cancel' },
      { text: 'Sıfırla', style: 'destructive', onPress: async () => {
        setConfigs([]);
        setScripts(DEFAULT_SCRIPTS);
        setSelectedConfigId(null);
        setSelectedScriptId(null);
        if (XHermesModule) await XHermesModule.saveConfigs('[]');
        showToast('Tüm veriler sıfırlandı');
      }},
    ]);
  };

  // ── Computed ────────────────────────────────────────────────────────────────
  const selectedConfig = configs.find(c => c.id === selectedConfigId);
  const selectedScript = scripts.find(sc => sc.id === selectedScriptId);
  const activeHookCount = configs.filter(c => c.enabled).length;

  // ── Render ──────────────────────────────────────────────────────────────────
  if (loading && configs.length === 0) {
    return (
      <View style={s.loadingWrap}>
        <StatusBar barStyle="light-content" backgroundColor={C.bg} />
        <ActivityIndicator size="large" color={C.primary} />
        <Text style={s.loadingText}>Yükleniyor…</Text>
      </View>
    );
  }

  return (
    <SafeAreaView style={s.root}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />

      {/* ── Toast ──────────────────────────────────────────────────────── */}
      <Animated.View style={[s.toast, { opacity: toastOpacity }]} pointerEvents="none">
        <Text style={s.toastText}>{toastMessage}</Text>
      </Animated.View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={{ flex: 1 }}>

        {/* ── Content ────────────────────────────────────────────────── */}
        <View style={{ flex: 1 }}>
          <View style={{ height: ADAPTIVE_TOP_OFFSET + uiOffset }} />

          {/* ═══════════ HOME TAB ═══════════ */}
          {currentTab === 'home' && (
            <ScrollView contentContainerStyle={s.scroll} showsVerticalScrollIndicator={false}>
              {/* Hero status card */}
              <View style={[s.heroCard, { borderColor: isActive ? C.successContainer : C.errorContainer }]}>
                <View style={s.heroTop}>
                  <View style={s.heroIconWrap}>
                    <Animated.View style={[
                      s.heroPulse,
                      { backgroundColor: isActive ? C.success : C.error, opacity: pulseAnim }
                    ]} />
                    <View style={[s.heroDot, { backgroundColor: isActive ? C.success : C.error }]} />
                  </View>
                  <View style={s.heroTextWrap}>
                    <Text style={s.heroTitle}>XHermes</Text>
                    <Text style={s.heroVersion}>v1.2.0 · libxposed API 101</Text>
                  </View>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <View style={[s.statusBadge, { backgroundColor: isActive ? C.successContainer : C.errorContainer }]}>
                    <Text style={[s.statusBadgeText, { color: isActive ? C.success : C.error }]}>
                      {isActive ? 'Framework Aktif' : 'Framework Pasif'}
                    </Text>
                  </View>
                  <TouchableOpacity style={s.refreshBtn} onPress={handleResetHookStatus} activeOpacity={0.7}>
                    <Text style={s.refreshBtnText}>↻ Kancaları Temizle</Text>
                  </TouchableOpacity>
                </View>
                {!isActive && (
                  <Text style={s.heroHint}>
                    Modülün çalışması için LSPosed/LSPatch Manager'da XHermes etkinleştirilmelidir.
                  </Text>
                )}
              </View>

              {/* Stats row */}
              <View style={s.statsRow}>
                <View style={s.statCard}>
                  <Text style={s.statValue}>{configs.length}</Text>
                  <Text style={s.statLabel}>Uygulama</Text>
                </View>
                <View style={s.statCard}>
                  <Text style={[s.statValue, { color: C.success }]}>{activeHookCount}</Text>
                  <Text style={s.statLabel}>Aktif Kanca</Text>
                </View>
                <View style={s.statCard}>
                  <Text style={s.statValue}>{scripts.length}</Text>
                  <Text style={s.statLabel}>Script</Text>
                </View>
              </View>

              {/* Quick guide */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Başlarken</Text>
                <View style={s.guideStep}>
                  <View style={s.guideNum}><Text style={s.guideNumText}>1</Text></View>
                  <Text style={s.guideText}>
                    <Text style={s.bold}>Uygulamalar</Text> sekmesinden hedef uygulamayı seçin veya paket adını girin.
                  </Text>
                </View>
                <View style={s.guideStep}>
                  <View style={s.guideNum}><Text style={s.guideNumText}>2</Text></View>
                  <Text style={s.guideText}>
                    <Text style={s.bold}>Scriptler</Text> sekmesinde enjekte edilecek JS kodlarını oluşturun.
                  </Text>
                </View>
                <View style={s.guideStep}>
                  <View style={s.guideNum}><Text style={s.guideNumText}>3</Text></View>
                  <Text style={s.guideText}>
                    LSPosed Manager'da hedef uygulamayı XHermes kapsamına ekleyin ve yeniden başlatın.
                  </Text>
                </View>
              </View>

              {/* Active hooks list */}
              {activeHookCount > 0 && (
                <View style={s.card}>
                  <Text style={s.cardTitle}>Aktif Kancalar</Text>
                  {configs.filter(c => c.enabled).map(c => (
                    <View key={c.id} style={s.hookItem}>
                      <View style={s.hookDot} />
                      <Text style={s.hookText}>{c.targetPackage}</Text>
                      {c.injectWebView && (
                        <View style={s.hookBadge}><Text style={s.hookBadgeText}>WebView</Text></View>
                      )}
                    </View>
                  ))}
                </View>
              )}
            </ScrollView>
          )}

          {/* ═══════════ APPS TAB ═══════════ */}
          {currentTab === 'apps' && (
            <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}>
              {/* Search & Add */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Uygulama Ekle</Text>
                <TextInput
                  style={s.input}
                  placeholder="Uygulama veya paket adı ara…"
                  placeholderTextColor={C.outline}
                  value={searchQuery}
                  onChangeText={setSearchQuery}
                />

                <TouchableOpacity style={s.filterRow} onPress={() => setFilterRNOnly(!filterRNOnly)} activeOpacity={0.7}>
                  <Text style={s.filterText}>Sadece React Native uygulamalar</Text>
                  <Switch
                    value={filterRNOnly}
                    onValueChange={setFilterRNOnly}
                    trackColor={{ false: C.outlineVar, true: C.primaryContainer }}
                    thumbColor={filterRNOnly ? C.primary : C.outline}
                  />
                </TouchableOpacity>

                <View style={s.appListContainer}>
                  <ScrollView style={s.appListScroll} nestedScrollEnabled>
                    {installedApps
                      .filter(app => {
                        const q = searchQuery.toLowerCase();
                        const match = app.label.toLowerCase().includes(q) || app.packageName.toLowerCase().includes(q);
                        return filterRNOnly ? (match && app.isReactNative) : match;
                      })
                      .map(app => (
                        <TouchableOpacity key={app.packageName} style={s.appItem} onPress={() => handleAddNewPackage(app.packageName)} activeOpacity={0.6}>
                          <View style={{ flex: 1, marginRight: 8 }}>
                            <Text style={s.appLabel} numberOfLines={1}>{app.label}</Text>
                            <Text style={s.appPkg} numberOfLines={1}>{app.packageName}</Text>
                          </View>
                          {app.isReactNative && <View style={s.rnBadge}><Text style={s.rnBadgeText}>RN</Text></View>}
                        </TouchableOpacity>
                      ))}
                  </ScrollView>
                </View>

                <Divider />
                <Text style={s.miniLabel}>Manuel Paket Ekle</Text>
                <View style={s.addRow}>
                  <TextInput
                    style={[s.input, { flex: 1, marginRight: 10, marginBottom: 0 }]}
                    placeholder="com.target.app"
                    placeholderTextColor={C.outline}
                    value={newPackageInput}
                    onChangeText={setNewPackageInput}
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                  <TouchableOpacity style={s.btnPrimary} onPress={() => handleAddNewPackage(newPackageInput)}>
                    <Text style={s.btnPrimaryText}>Ekle</Text>
                  </TouchableOpacity>
                </View>
              </View>

              {/* Registered packages */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Kayıtlı Uygulamalar ({configs.length})</Text>
                {configs.length === 0 ? (
                  <Text style={s.emptyText}>Henüz uygulama eklenmedi.</Text>
                ) : (
                  configs.map(config => {
                    const isHooked = hookedPackages.includes(config.targetPackage);
                    return (
                    <View key={config.id} style={[s.pkgItem, selectedConfigId === config.id && s.pkgItemActive]}>
                      <TouchableOpacity style={s.pkgTouch} onPress={() => setSelectedConfigId(config.id)}>
                        <View style={[s.pkgDot, { backgroundColor: config.enabled ? C.success : C.outlineVar }]} />
                        <View style={{ flex: 1, marginRight: 8 }}>
                          <Text style={[s.pkgName, selectedConfigId === config.id && { color: C.primary }]} numberOfLines={1} ellipsizeMode="middle">
                            {config.targetPackage}
                          </Text>
                          <View style={{ flexDirection: 'row', marginTop: 4 }}>
                            {isHooked ? (
                              <View style={s.hookedBadge}><Text style={s.hookedBadgeText}>LSP ✓</Text></View>
                            ) : (
                              <View style={s.notHookedBadge}><Text style={s.notHookedBadgeText}>Kapsam Dışı</Text></View>
                            )}
                          </View>
                        </View>
                      </TouchableOpacity>
                      <TouchableOpacity style={s.pkgDel} onPress={() => handleDeletePackage(config.id)}>
                        <Text style={s.pkgDelText}>{ICONS.remove}</Text>
                      </TouchableOpacity>
                    </View>
                  )})
                )}
              </View>

              {/* Selected config detail */}
              {selectedConfig && (
                <View style={s.card}>
                  <Text style={s.cardTitle}>{selectedConfig.targetPackage}</Text>

                  <SettingRow
                    title="Enjeksiyonu Etkinleştir"
                    description="Bu uygulama için tüm kancalamaları etkinleştirir."
                    value={selectedConfig.enabled}
                    onToggle={(val) => updateConfigField('enabled', val)}
                  />
                  <Divider />
                  <SettingRow
                    title="Orijinal Bundle'ı Engelle"
                    description="Uygulamanın kendi JS kodunun yüklenmesini engeller."
                    value={selectedConfig.blockOriginalBundle}
                    onToggle={(val) => updateConfigField('blockOriginalBundle', val)}
                  />
                  <Divider />
                  <SettingRow
                    title="WebView Enjekte Et"
                    description="React Native yerine tam ekran WebView yükler."
                    value={selectedConfig.injectWebView && selectedConfig.blockOriginalBundle}
                    onToggle={(val) => updateConfigField('injectWebView', val)}
                    disabled={!selectedConfig.blockOriginalBundle}
                  />
                  <Divider />
                  <SettingRow
                    title="Hollow Process (Agresif İçi Boş Süreç)"
                    description="Uygulamanın TTI/Analitik sınıflarını ve ContentProvider'larını en ilk kapıda durdurarak tamamen içi boş bir kabuk yaratır."
                    value={selectedConfig.hollowProcess || false}
                    onToggle={(val) => updateConfigField('hollowProcess', val)}
                  />

                  {selectedConfig.injectWebView && selectedConfig.blockOriginalBundle && (
                    <>
                      <View style={{ paddingHorizontal: 4, marginTop: 12 }}>
                        <Text style={s.miniLabel}>WebView URL</Text>
                        <TextInput
                          style={[s.input, { marginBottom: 0 }]}
                          placeholder="https://example.com"
                          placeholderTextColor={C.outline}
                          value={selectedConfig.webViewUrl}
                          onChangeText={(val) => updateConfigField('webViewUrl', val)}
                          autoCapitalize="none"
                          autoCorrect={false}
                          keyboardType="url"
                        />
                      </View>
                      <Divider />
                      <SettingRow
                        title="WebView Debugging"
                        description="Chrome DevTools ile uzaktan hata ayıklamayı açar."
                        value={selectedConfig.enableWebViewDebugging && selectedConfig.injectWebView}
                        onToggle={(val) => updateConfigField('enableWebViewDebugging', val)}
                        disabled={!selectedConfig.injectWebView}
                      />
                    </>
                  )}

                  <Divider />

                  {/* Pre Script assignment */}
                  <View style={[selectedConfig.injectWebView && { opacity: 0.4 }]}>
                    <SettingRow
                      title="Öncül Script (Pre)"
                      value={selectedConfig.preScriptEnabled && !selectedConfig.injectWebView}
                      onToggle={(val) => updateConfigField('preScriptEnabled', val)}
                      disabled={selectedConfig.injectWebView}
                    />
                    <Text style={s.miniLabel}>Bağlı script seçin:</Text>
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingVertical: 4 }}>
                      {scripts.filter(sc => sc.type === 'pre').map(sc => (
                        <Pill
                          key={sc.id}
                          label={sc.name}
                          selected={selectedConfig.preScriptId === sc.id}
                          onPress={() => updateConfigField('preScriptId', sc.id)}
                          disabled={selectedConfig.injectWebView}
                        />
                      ))}
                    </ScrollView>
                  </View>

                  <Divider />

                  {/* Post Script assignment */}
                  <View style={[selectedConfig.injectWebView && { opacity: 0.4 }]}>
                    <SettingRow
                      title="Ardıl Script (Post)"
                      value={selectedConfig.postScriptEnabled && !selectedConfig.injectWebView}
                      onToggle={(val) => updateConfigField('postScriptEnabled', val)}
                      disabled={selectedConfig.injectWebView}
                    />
                    <Text style={s.miniLabel}>Bağlı script seçin:</Text>
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingVertical: 4 }}>
                      {scripts.filter(sc => sc.type === 'post').map(sc => (
                        <Pill
                          key={sc.id}
                          label={sc.name}
                          selected={selectedConfig.postScriptId === sc.id}
                          onPress={() => updateConfigField('postScriptId', sc.id)}
                          disabled={selectedConfig.injectWebView}
                        />
                      ))}
                    </ScrollView>
                  </View>
                </View>
              )}
            </ScrollView>
          )}

          {/* ═══════════ SCRIPTS TAB ═══════════ */}
          {currentTab === 'scripts' && (
            <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}>
              {/* New script form */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Yeni Script Oluştur</Text>
                <TextInput
                  style={s.input}
                  placeholder="Script Adı"
                  placeholderTextColor={C.outline}
                  value={newScriptName}
                  onChangeText={setNewScriptName}
                />
                <View style={s.typeRow}>
                  <Text style={s.miniLabel}>Tip: </Text>
                  <Pill label="Öncül (Pre)" selected={newScriptType === 'pre'} onPress={() => setNewScriptType('pre')} />
                  <Pill label="Ardıl (Post)" selected={newScriptType === 'post'} onPress={() => setNewScriptType('post')} />
                </View>
                <TextInput
                  style={s.codeEditor}
                  multiline
                  placeholder="// JavaScript kodlarınızı yazın…"
                  placeholderTextColor={C.outline}
                  value={newScriptCode}
                  onChangeText={setNewScriptCode}
                  autoCapitalize="none"
                  autoCorrect={false}
                />
                <TouchableOpacity style={[s.btnPrimary, { marginTop: 12, alignSelf: 'stretch' }]} onPress={handleAddNewScript}>
                  <Text style={s.btnPrimaryText}>Kütüphaneye Kaydet</Text>
                </TouchableOpacity>
              </View>

              {/* Script library */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Script Kütüphanesi ({scripts.length})</Text>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingVertical: 4 }}>
                  {scripts.map(sc => (
                    <Pill
                      key={sc.id}
                      label={`${sc.name} (${sc.type === 'pre' ? 'Pre' : 'Post'})`}
                      selected={selectedScriptId === sc.id}
                      onPress={() => { setSelectedScriptId(sc.id); setNewScriptCode(sc.code); }}
                    />
                  ))}
                </ScrollView>
              </View>

              {/* Edit selected script */}
              {selectedScript && (
                <View style={s.card}>
                  <Text style={s.cardTitle}>Düzenle: {selectedScript.name}</Text>
                  <TextInput
                    style={[s.codeEditor, { height: 260 }]}
                    multiline
                    value={selectedScript.code}
                    onChangeText={(val) => handleUpdateScript(selectedScript.id, val)}
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                  <Text style={s.hintText}>Değişiklikler otomatik kaydedilir.</Text>
                  {!selectedScript.id.startsWith('default-') && (
                    <TouchableOpacity style={s.btnDanger} onPress={() => handleDeleteScript(selectedScript.id)}>
                      <Text style={s.btnDangerText}>Scripti Kalıcı Olarak Sil</Text>
                    </TouchableOpacity>
                  )}
                </View>
              )}
            </ScrollView>
          )}

          {/* ═══════════ SETTINGS TAB ═══════════ */}
          {currentTab === 'settings' && (
            <ScrollView contentContainerStyle={s.scroll} showsVerticalScrollIndicator={false}>
              {/* Accessibility offset */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Ekran Konumu Ayarla</Text>
                <Text style={s.cardDesc}>Dokunmatik ölü bölgeler varsa arayüzü kaydırın.</Text>
                <View style={s.offsetRow}>
                  <TouchableOpacity style={s.offsetBtn} onPress={() => setUiOffset(prev => Math.max(0, prev - 30))}>
                    <Text style={s.offsetBtnText}>↑ Yukarı</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={[s.offsetBtn, { backgroundColor: C.surfaceBright }]} onPress={() => setUiOffset(0)}>
                    <Text style={s.offsetBtnText}>Sıfırla</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={s.offsetBtn} onPress={() => setUiOffset(prev => Math.min(300, prev + 30))}>
                    <Text style={s.offsetBtnText}>↓ Aşağı</Text>
                  </TouchableOpacity>
                </View>
                {uiOffset > 0 && (
                  <Text style={[s.hintText, { textAlign: 'center', marginTop: 10, color: C.primary }]}>
                    Ofset: {uiOffset}px
                  </Text>
                )}
              </View>

              {/* About */}
              <View style={s.card}>
                <Text style={s.cardTitle}>Hakkında</Text>
                <View style={s.aboutRow}><Text style={s.aboutLabel}>Sürüm</Text><Text style={s.aboutValue}>1.2.0</Text></View>
                <View style={s.aboutRow}><Text style={s.aboutLabel}>Geliştirici</Text><Text style={s.aboutValue}>GokturkA</Text></View>
                <View style={s.aboutRow}><Text style={s.aboutLabel}>Framework</Text><Text style={s.aboutValue}>io.github.libxposed (API 101)</Text></View>
                <View style={s.aboutRow}><Text style={s.aboutLabel}>Modül Durumu</Text><Text style={[s.aboutValue, { color: isActive ? C.success : C.error }]}>{isActive ? 'Aktif' : 'Pasif'}</Text></View>
              </View>

              {/* Danger zone */}
              <View style={[s.card, { borderColor: C.errorContainer }]}>
                <Text style={[s.cardTitle, { color: C.error }]}>Tehlikeli Bölge</Text>
                <Text style={s.cardDesc}>
                  Bu işlem tüm uygulama yapılandırmalarını ve özel scriptleri kalıcı olarak siler.
                </Text>
                <TouchableOpacity style={s.btnDanger} onPress={resetAllData}>
                  <Text style={s.btnDangerText}>Tüm Verileri Sıfırla</Text>
                </TouchableOpacity>
              </View>
            </ScrollView>
          )}
        </View>

        {/* ── Bottom Navigation Bar ──────────────────────────────────── */}
        <View style={s.bottomNav}>
          {([
            { key: 'home', label: 'Ana Sayfa', icon: ICONS.home },
            { key: 'apps', label: 'Uygulamalar', icon: ICONS.apps },
            { key: 'scripts', label: 'Scriptler', icon: ICONS.code },
            { key: 'settings', label: 'Ayarlar', icon: ICONS.settings },
          ] as const).map(tab => {
            const active = currentTab === tab.key;
            return (
              <TouchableOpacity
                key={tab.key}
                style={s.navItem}
                onPress={() => setCurrentTab(tab.key)}
                activeOpacity={0.7}
              >
                <View style={[s.navIconWrap, active && s.navIconWrapActive]}>
                  <Text style={[s.navIcon, active && s.navIconActive]}>{tab.icon}</Text>
                </View>
                <Text style={[s.navLabel, active && s.navLabelActive]}>{tab.label}</Text>
              </TouchableOpacity>
            );
          })}
        </View>

      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────
const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
  },
  loadingWrap: {
    flex: 1,
    backgroundColor: C.bg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    color: C.primary,
    marginTop: 16,
    fontSize: 15,
    fontWeight: '600',
  },
  scroll: {
    padding: 16,
    paddingBottom: 24,
  },

  // ─ Toast ─
  toast: {
    position: 'absolute',
    bottom: 100,
    left: 24,
    right: 24,
    backgroundColor: C.surfaceHigh,
    paddingVertical: 14,
    paddingHorizontal: 20,
    borderRadius: 16,
    alignItems: 'center',
    zIndex: 9999,
    elevation: 8,
    borderWidth: 1,
    borderColor: C.outlineVar,
  },
  toastText: {
    color: C.onSurface,
    fontSize: 14,
    fontWeight: '500',
  },

  // ─ Hero Card ─
  heroCard: {
    backgroundColor: C.surfaceContainer,
    borderRadius: 24,
    padding: 20,
    marginBottom: 16,
    borderWidth: 1,
  },
  heroTop: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
  },
  heroIconWrap: {
    width: 48,
    height: 48,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 14,
  },
  heroPulse: {
    position: 'absolute',
    width: 48,
    height: 48,
    borderRadius: 24,
  },
  heroDot: {
    width: 16,
    height: 16,
    borderRadius: 8,
  },
  heroTextWrap: {
    flex: 1,
  },
  heroTitle: {
    fontSize: 28,
    fontWeight: '700',
    color: C.onSurface,
    letterSpacing: -0.5,
  },
  heroVersion: {
    fontSize: 13,
    color: C.onSurfaceVar,
    marginTop: 2,
  },
  statusBadge: {
    alignSelf: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 20,
  },
  statusBadgeText: {
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  heroHint: {
    fontSize: 12,
    color: C.onSurfaceVar,
    marginTop: 10,
    lineHeight: 17,
  },

  // ─ Stats ─
  statsRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 16,
  },
  statCard: {
    flex: 1,
    backgroundColor: C.surfaceContainer,
    borderRadius: 16,
    paddingVertical: 16,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: C.outlineVar,
  },
  statValue: {
    fontSize: 26,
    fontWeight: '700',
    color: C.primary,
  },
  statLabel: {
    fontSize: 11,
    color: C.onSurfaceVar,
    marginTop: 4,
    fontWeight: '500',
  },

  // ─ Card ─
  card: {
    backgroundColor: C.surfaceContainer,
    borderRadius: 20,
    padding: 18,
    marginBottom: 14,
    borderWidth: 1,
    borderColor: C.outlineVar,
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: C.onSurface,
    marginBottom: 14,
    letterSpacing: -0.2,
  },
  cardDesc: {
    fontSize: 13,
    color: C.onSurfaceVar,
    lineHeight: 19,
    marginBottom: 8,
  },

  // ─ Guide Steps ─
  guideStep: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    marginBottom: 12,
  },
  guideNum: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: C.primaryContainer,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
    marginTop: 1,
  },
  guideNumText: {
    color: C.primary,
    fontSize: 12,
    fontWeight: '700',
  },
  guideText: {
    flex: 1,
    fontSize: 13,
    color: C.onSurfaceVar,
    lineHeight: 19,
  },
  bold: {
    fontWeight: '700',
    color: C.primary,
  },

  // ─ Hook List ─
  hookItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: C.outlineVar,
  },
  hookDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: C.success,
    marginRight: 12,
  },
  hookText: {
    flex: 1,
    color: C.onSurface,
    fontSize: 13,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  hookBadge: {
    backgroundColor: C.tertiaryContainer,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 8,
    marginLeft: 8,
  },
  hookBadgeText: {
    color: C.tertiary,
    fontSize: 10,
    fontWeight: '700',
  },

  // ─ Input ─
  input: {
    backgroundColor: C.surfaceLowest,
    borderColor: C.outlineVar,
    borderWidth: 1,
    borderRadius: 14,
    color: C.onSurface,
    paddingHorizontal: 16,
    paddingVertical: 12,
    fontSize: 14,
    marginBottom: 12,
  },

  // ─ Filter ─
  filterRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
    paddingHorizontal: 4,
  },
  filterText: {
    color: C.onSurface,
    fontSize: 14,
    fontWeight: '500',
  },

  // ─ App List ─
  appListContainer: {
    height: 220,
    borderRadius: 14,
    borderColor: C.outlineVar,
    borderWidth: 1,
    backgroundColor: C.surfaceLowest,
    overflow: 'hidden',
    marginBottom: 12,
  },
  appListScroll: {
    flex: 1,
  },
  appItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderBottomWidth: 1,
    borderBottomColor: C.outlineVar,
  },
  appLabel: {
    color: C.onSurface,
    fontSize: 14,
    fontWeight: '600',
  },
  appPkg: {
    color: C.onSurfaceVar,
    fontSize: 11,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    marginTop: 2,
  },
  rnBadge: {
    backgroundColor: C.primaryContainer,
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  rnBadgeText: {
    color: C.primary,
    fontSize: 10,
    fontWeight: '700',
  },

  // ─ Add Row ─
  addRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },

  // ─ Buttons ─
  btnPrimary: {
    backgroundColor: C.primaryContainer,
    borderRadius: 14,
    paddingHorizontal: 22,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnPrimaryText: {
    color: C.primary,
    fontSize: 14,
    fontWeight: '700',
  },
  btnDanger: {
    backgroundColor: C.errorContainer,
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 14,
  },
  btnDangerText: {
    color: C.error,
    fontSize: 14,
    fontWeight: '700',
  },

  // ─ Package List ─
  pkgItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: C.surfaceLowest,
    borderRadius: 14,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: C.outlineVar,
    overflow: 'hidden',
  },
  pkgItemActive: {
    borderColor: C.primary,
    backgroundColor: C.secondaryContainer,
  },
  pkgTouch: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 16,
    paddingHorizontal: 16,
  },
  pkgDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 12,
  },
  pkgName: {
    flex: 1,
    color: C.onSurface,
    fontSize: 14,
    fontWeight: '600',
  },
  pkgDel: {
    paddingHorizontal: 18,
    paddingVertical: 18,
  },
  pkgDelText: {
    color: C.error,
    fontSize: 16,
    fontWeight: '700',
  },

  // ─ Setting Row ─
  settingRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 14,
    paddingHorizontal: 4,
  },
  settingTextWrap: {
    flex: 1,
    marginRight: 14,
  },
  settingTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: C.onSurface,
  },
  settingDesc: {
    fontSize: 12,
    color: C.onSurfaceVar,
    lineHeight: 17,
    marginTop: 3,
  },

  // ─ Divider ─
  divider: {
    height: 1,
    backgroundColor: C.outlineVar,
    marginVertical: 12,
  },

  // ─ Pill ─
  pill: {
    borderColor: C.outlineVar,
    borderWidth: 1,
    borderRadius: 100,
    paddingHorizontal: 18,
    paddingVertical: 10,
    marginRight: 8,
    marginBottom: 4,
  },
  pillSelected: {
    borderColor: C.primary,
    backgroundColor: C.primaryContainer,
  },
  pillText: {
    color: C.onSurfaceVar,
    fontSize: 12,
    fontWeight: '600',
  },
  pillTextSelected: {
    color: C.primary,
    fontWeight: '700',
  },

  // ─ Labels / Hints ─
  miniLabel: {
    color: C.onSurfaceVar,
    fontSize: 12,
    fontWeight: '600',
    marginBottom: 8,
  },
  emptyText: {
    color: C.onSurfaceVar,
    fontSize: 13,
    textAlign: 'center',
    paddingVertical: 20,
  },
  hintText: {
    fontSize: 12,
    color: C.onSurfaceVar,
    marginTop: 10,
    lineHeight: 16,
  },

  // ─ Code Editor ─
  codeEditor: {
    backgroundColor: C.surfaceLowest,
    borderColor: C.outlineVar,
    borderWidth: 1,
    borderRadius: 14,
    color: C.primary,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 13,
    padding: 14,
    height: 150,
    textAlignVertical: 'top',
  },

  // ─ Type Row ─
  typeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },

  // ─ Section Header ─
  sectionHeader: {
    marginBottom: 14,
  },
  sectionTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: C.onSurface,
    letterSpacing: -0.3,
  },
  sectionSubtitle: {
    fontSize: 13,
    color: C.onSurfaceVar,
    marginTop: 2,
  },

  // ─ About ─
  aboutRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: C.outlineVar,
  },
  aboutLabel: {
    fontSize: 14,
    color: C.onSurfaceVar,
  },
  aboutValue: {
    fontSize: 14,
    color: C.onSurface,
    fontWeight: '600',
  },

  // ─ Offset ─
  offsetRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 12,
  },
  offsetBtn: {
    flex: 1,
    backgroundColor: C.primaryContainer,
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
  },
  offsetBtnText: {
    color: C.primary,
    fontSize: 13,
    fontWeight: '700',
  },

  refreshBtn: {
    backgroundColor: C.surfaceBright,
    borderRadius: 16,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderWidth: 1,
    borderColor: C.outlineVar,
  },
  refreshBtnText: {
    color: C.primary,
    fontSize: 12,
    fontWeight: '600',
  },

  // ─ Floating Bottom Nav Bar ─
  bottomNav: {
    flexDirection: 'row',
    backgroundColor: C.surfaceContainer,
    borderRadius: 24,
    marginHorizontal: 16,
    marginBottom: Platform.OS === 'ios' ? 24 : 14,
    paddingVertical: 8,
    paddingHorizontal: 4,
    borderWidth: 1,
    borderColor: C.outlineVar,
    shadowColor: C.scrim,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.35,
    shadowRadius: 8,
    elevation: 6,
  },
  navItem: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 4,
  },
  navIconWrap: {
    width: 56,
    height: 32,
    borderRadius: 16,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 4,
  },
  navIconWrapActive: {
    backgroundColor: C.secondaryContainer,
  },
  navIcon: {
    fontSize: 18,
    color: C.onSurfaceVar,
  },
  navIconActive: {
    color: C.onSecondaryContainer,
  },
  navLabel: {
    fontSize: 12,
    fontWeight: '500',
    color: C.onSurfaceVar,
  },
  navLabelActive: {
    color: C.onSecondaryContainer,
    fontWeight: '700',
  },

  // ─ Framework Hooked Badges ─
  hookedBadge: {
    backgroundColor: C.successContainer,
    borderRadius: 6,
    paddingHorizontal: 7,
    paddingVertical: 2,
  },
  hookedBadgeText: {
    color: C.success,
    fontSize: 10,
    fontWeight: '700',
  },
  notHookedBadge: {
    backgroundColor: C.errorContainer,
    borderRadius: 6,
    paddingHorizontal: 7,
    paddingVertical: 2,
  },
  notHookedBadgeText: {
    color: C.error,
    fontSize: 10,
    fontWeight: '700',
  },
});
