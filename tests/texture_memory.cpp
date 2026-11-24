#include "../tools/frontend/texture_memory.h"
#include <map>
#include <vector>
#include <cassert>
#include <memory>
struct Entry { uint64_t bytes, last; bool pinned; };
int main() {
    using namespace RetunedFrontend;
    // Both decoded caches share one platform budget.
    assert(RemainingDecodedTextureBudget(100, 40) == 60);
    assert(RemainingDecodedTextureBudget(100, 100) == 0);
    assert(RemainingDecodedTextureBudget(100, 120) == 0);
    assert(RemainingDecodedTextureBudget(0, 0) == 0);
    auto desktop=SelectDesktopTextureBudgets(16*GiB,8*GiB);
    assert(desktop.Decoded==GiB && desktop.Uploaded==GiB);
    desktop=SelectDesktopTextureBudgets(64*GiB,32*GiB);
    assert(desktop.Decoded==2*GiB && desktop.Uploaded==2*GiB);
    desktop=SelectDesktopTextureBudgets(16*GiB,GiB);
    assert(desktop.Decoded==128*MiB && desktop.Uploaded==128*MiB);
    auto low=SelectMobileTextureBudgets(2*GiB,0);
    assert(low.Decoded==96*MiB && low.Uploaded==128*MiB);
    assert(TextureDecodeLimit(low.Decoded,false)==32*MiB);
    assert(TextureDecodeLimit(low.Decoded,true)==96*MiB);
    auto budget=SelectMobileTextureBudgets(3*GiB,0);
    assert(budget.Decoded==96*MiB && budget.Uploaded==192*MiB);
    budget=SelectMobileTextureBudgets(4*GiB,0);
    assert(budget.Decoded==128*MiB && budget.Uploaded==256*MiB);
    budget=SelectMobileTextureBudgets(8*GiB,0);
    assert(budget.Decoded==192*MiB && budget.Uploaded==384*MiB);
    budget=SelectMobileTextureBudgets(16*GiB,0);
    assert(budget.Decoded==256*MiB && budget.Uploaded==512*MiB);
    budget=SelectMobileTextureBudgets(12*GiB,2*GiB);
    assert(budget.Decoded==128*MiB && budget.Uploaded==256*MiB);
    budget=SelectMobileTextureBudgets(0,0);
    assert(budget.Decoded==128*MiB && budget.Uploaded==256*MiB);
    // A high-memory handheld must fit an 8K RGBA atlas plus the UI reserve.
    budget=SelectAndroidTextureBudgets(16*GiB,9*GiB);
    assert(budget.Decoded==GiB && budget.Uploaded==2*GiB);
    assert(TextureDecodeLimit(budget.Decoded,false)>=8192ULL*8192*4);
    budget=SelectAndroidTextureBudgets(16*GiB,3*GiB);
    assert(budget.Decoded+budget.Uploaded==GiB);
    budget=SelectAndroidTextureBudgets(4*GiB,3*GiB);
    assert(budget.Decoded==128*MiB && budget.Uploaded==256*MiB);
    budget=SelectAndroidTextureBudgets(16*GiB,256*MiB);
    assert(budget.Decoded==96*MiB && budget.Uploaded==192*MiB);
    budget=SelectAndroidTextureBudgets(64*GiB,UINT64_MAX);
    assert(budget.Decoded+budget.Uploaded==4*GiB);

    auto cost=[](const auto& e){return e.second.bytes;};
    auto age=[](const auto& e){return e.second.last;};
    auto eligible=[](const auto& e){return !e.second.pinned;};
    std::vector<int> retired;
    auto retire=[&](const auto& e){retired.push_back(e.first);};
    std::map<int, Entry> materials{{1,{40,1,false}}, {2,{40,2,false}}};
    std::map<int, Entry> replacements{{3,{40,3,false}}, {4,{40,4,false}}};
    const auto shared=TrimSharedTextureCaches(materials,replacements,100,cost,cost,age,eligible,retire);
    assert(shared.first==40 && shared.second==40);
    assert(shared.first+shared.second<=100);
    assert(materials.size()==1 && replacements.size()==1);
    assert((retired==std::vector<int>{1,3}));

    // Protected entries may exceed the shared budget, but the other cache
    // must not grow while that over-budget entry remains pinned.
    std::map<int, Entry> protectedMaterials{{5,{120,1,true}}};
    std::map<int, Entry> sceneReplacements{{6,{40,2,false}}};
    const auto protectedResult=TrimSharedTextureCaches(
        protectedMaterials,sceneReplacements,100,cost,cost,age,eligible,retire);
    assert(protectedResult.first==120 && protectedResult.second==0);
    assert(protectedResult.first+protectedResult.second==120);
    assert(sceneReplacements.empty());
    std::map<int, Entry> pinnedMaterials{{7,{120,1,true}}};
    std::map<int, Entry> pinnedReplacements{{8,{80,2,true}}};
    const auto bothPinned=TrimSharedTextureCaches(
        pinnedMaterials,pinnedReplacements,100,cost,cost,age,eligible,retire);
    assert(bothPinned.first==120 && bothPinned.second==80);
    assert(bothPinned.first+bothPinned.second==200);

    assert(FitsTextureBudget(80,20,100));
    assert(!FitsTextureBudget(80,21,100));
    assert(!FitsTextureBudget(UINT64_MAX,1,100));
    assert(!FitsTextureBudget(0,101,100));
    assert(TrimTextureCache(materials,100,cost,age,eligible,retire)==40);
    struct Texture { uint32_t Width, Height; bool CustomReplacementApplied; uint64_t CustomReplacementHash=0; };
    struct Frame { std::vector<Texture> RetiredTextures; };
    std::map<int,Texture> images{{1,{4,4,true}}, {2,{8,8,false}}};
    std::vector<Frame> frames{{{{4,4,true}}}};
    assert(UploadedTextureBytes(images,frames)==128);
    assert(!FitsTextureBudget(UploadedTextureBytes(images,frames),64,128));
    frames.clear();
    assert(FitsTextureBudget(UploadedTextureBytes(images,frames),64,128));
    // Eviction releases only cache ownership; an active reader stays valid.
    auto reader=std::make_shared<int>(42);
    std::map<int,std::shared_ptr<int>> decoded{{1,reader}};
    TrimTextureCache(decoded,0,[](const auto&){return 4ULL;},
        [](const auto&){return 0ULL;},[](const auto&){return true;},[](const auto&){});
    assert(decoded.empty() && *reader==42);
    struct Alias { uint64_t Native; };
    TextureBudgetConfiguration().Decoded.store(128*MiB);
    TextureBudgetConfiguration().Uploaded.store(256*MiB);
    PublishInterfaceTextureHashes(std::vector<Alias>{{123}});
    assert(IsInterfaceTexture(123) && !IsInterfaceTexture(456));
    // Scene uploads leave room for the 4K interface atlas; it still obeys the total cap.
    images={{1,{8192,6144,true,456}}};
    frames.clear();
    assert(!FitsReplacementBudget(images,frames,4,456));
    assert(FitsReplacementBudget(images,frames,64ULL*1024*1024,123));
    images.emplace(2,Texture{4096,4096,true,123});
    assert(!FitsReplacementBudget(images,frames,4,123));
}
