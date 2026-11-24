# Vulkan 1.1 compatibility assessment

**Status:** investigation only. The Android renderer still requires Vulkan 1.2.
This document records the current blockers before any minimum-version change.
It is not a claim that Vulkan 1.1 devices already work.

The Android build currently pins TriAevum to
`17218b0cfdb944645bb5cde5249f313dca7e53ce`.

## Confirmed Vulkan 1.2 contracts

| Area | Current implementation | Why changing one version constant is insufficient |
|---|---|---|
| Instance creation | `PicaVulkanApplicationInfo()` sets `apiVersion = VK_API_VERSION_1_2`. | The same application contract is used by live rendering and pipeline preparation; the source explicitly notes that API versions can change pipeline-cache UUIDs. |
| Adapter selection | `VulkanAdapterCandidate` defaults to API 1.2 and `IsVulkanAdapterSuitable()` rejects devices below 1.2. | Adapter selection, error reporting and preparation must agree on the selected API profile. |
| Device feature query | `gfx_vulkan_device.cpp` chains `VkPhysicalDeviceVulkan12Features` into `VkPhysicalDeviceFeatures2`. | A Vulkan 1.1 path must query promoted features through their extension feature structures and enable the matching extensions, rather than blindly passing the Vulkan 1.2 structure. |
| Optional device features | `PicaVulkan12Features()` copies buffer device address, timeline semaphores, shader float16 and descriptor-indexing features. NIS and FSR capability checks depend on subsets of these features. | Each feature must be classified as required for the base renderer, optional for an enhancement, or unused on Android. |
| Optional synchronization/rendering | Synchronization2 and dynamic rendering are enabled only when the adapter is Vulkan 1.2+ and the relevant KHR extension and feature are available. | The current gates intentionally do not activate the extension route on a 1.1 device. |
| ImGui backend | `gfx_vulkan.cpp` sets `ImGui_ImplVulkan_InitInfo.ApiVersion` to 1.2. | The UI backend must use the same negotiated API profile as the renderer. |
| NRI shader preparation | `oot3d_nri_vulkan12_shaders.cmake` forces ShaderMake to target Vulkan 1.2. | A 1.1 profile needs compatible SPIR-V output and must be shared by offline preparation and runtime compilation. |
| NRI depth resolve | `oot3d_nri_vulkan12_depth_resolve.cmake` patches NRI v180 validation for the current Vulkan 1.2 attachment-resolve path. | The 1.1 path needs a separately validated extension/feature route; removing the validation check alone would not establish device support. |

The adapter policy already produces an actionable reason when a presentation-capable device is below 1.2: it reports the detected Vulkan version and the current minimum. That behavior should be preserved until a real 1.1 backend path exists.

## Candidate feature classification

These are investigation targets, not approved requirements or a completed port:

- **Base renderer:** determine the exact Vulkan entry points, render-pass behavior, image formats, synchronization and shader capabilities that are actually used in the normal Android path.
- **Promoted 1.2 features:** evaluate extension routes for timeline semaphores, buffer device address, descriptor indexing and shader float16. Candidate extension families include `VK_KHR_timeline_semaphore`, `VK_KHR_buffer_device_address`, `VK_EXT_descriptor_indexing` and `VK_KHR_shader_float16_int8`. Each must be checked against the exact NRI v180 implementation and the Android driver.
- **Optional effects:** keep NIS, FSR, pipeline libraries, Brixelizer and other advanced effects independently capability-gated. A device lacking an optional feature must not be rejected if the base renderer does not need it.
- **Render-pass/depth resolve:** audit the exact NRI calls and the Vulkan 1.1 extension equivalents, including feature/property queries. Do not infer support from an extension name alone.
- **Shader compatibility:** build and validate the same shader set for Vulkan 1.1 and 1.2. The offline pipeline and runtime must agree on SPIR-V target, specialization behavior and cache identity.

## Safe implementation sequence

1. Add a capability report that records the instance version, physical-device API version, relevant extension presence and feature bits without attempting to create a Vulkan 1.1 device.
2. Build a required-versus-optional feature matrix from the Android release configuration and NRI v180 source.
3. Introduce a negotiated Vulkan API profile shared by runtime, ImGui, pipeline preparation and cache identity.
4. Add the Vulkan 1.1 extension feature chain and explicit fallbacks. Keep Vulkan 1.2 as the default and preserve the existing path.
5. Add policy tests for 1.1 devices with complete extension support, missing individual extensions, missing feature bits and Vulkan 1.2 devices.
6. Validate rendering correctness and GPU timestamps on real Vulkan 1.1 hardware before advertising the lower minimum.

## Acceptance criteria

A Vulkan 1.1 tier is not ready until it can:

- initialize the instance and device without passing Vulkan 1.2-only structures to a 1.1 implementation;
- render the title, UI, Kokiri Forest, Hyrule Field and scene transitions without validation errors or missing resources;
- preserve save/load behavior and visual correctness;
- run with optional upscalers and advanced effects disabled when their requirements are absent;
- pass shader/pipeline preparation and cache invalidation tests;
- produce stable frame-time and GPU-timestamp captures on more than one Vulkan 1.1 GPU/driver family.

Until then, Vulkan 1.2 remains the honest minimum. A compatibility label must follow tested behavior, not the Android API level or chipset name alone.
