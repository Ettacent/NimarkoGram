/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Nimarko for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import org.telegram.messenger.BuildVars;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.RoundVideoSettingsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.InstantCameraViewBase;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.stream.Collectors;

import app.nimarkogram.messenger.camera.CameraTypeSelector;
import app.nimarkogram.messenger.camera.CameraXUtils;
import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.utils.ResourcesUtils;

public class CameraPreferencesActivity extends UniversalPreferencesActivity {

    private final int cameraTypeSelectorRow = 2;

    private final int disableAttachCameraRow = 1;

    private final int cameraUseDualCameraRow = 3;
    private final int rearCamRow = 4;
    private final int startFromUltraWideRow = 5;
    private final int cameraStabilisationRow = 6;
    private final int cameraXQualityRow = 7;
    private final int cameraXFpsRangeRow = 8;

    private final int cameraControlButtonsRow = 10;

    private final int cameraImprovementsRow = 11;
    private final int opticalStabilizationRow = 12;
    private final int continuousFocusRow = 13;
    private final int noiseReductionRow = 14;
    private final int faceDetectionRow = 15;
    private final int useHighRangeRow = 16;

    private final int roundVideoSizeRow = 17;
    private final int roundVideoBitrateRow = 18;
    private final int roundVideoSettingsRow = 19;
    private final int smoothCameraModuleTransitionsRow = 20;
    private final int roundZoomScaleRow = 22;
    private final int cameraControlButtonsSideRow = 23;

    private final int roundVideoTapFocusRow = 24;
    private boolean cameraImprovementsExpanded = false;
    private CameraTypeSelector cameraTypeSelector;

    @Override
    protected CharSequence getTitle() {

        return getString(R.string.NM_Category_Camera);
    }

    @Override
    public View createView(Context context) {
        cameraTypeSelector = null;
        setMD3(true);
        return super.createView(context);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) listView.adapter.update(false);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean cameraX = CameraXUtils.isCurrentCameraCameraX();
        final boolean camera2 = AppConfig.cameraType == AppConfig.CAMERA_2;
        final boolean advanced = cameraX || camera2;   // Camera 2 / CameraX expose the extra knobs
        final boolean upstreamRoundCamera2 = InstantCameraViewBase.isUsingCamera2Implementation();

        if (CameraXUtils.isCameraXSupported()) {
            items.add(UItem.asHeader(-1, getString(R.string.NM_CameraType)));
            if (cameraTypeSelector == null) {
                cameraTypeSelector = new CameraTypeSelector(getContext()) {
                    @Override
                    protected void onSelectedCamera(int cameraSelected) {
                        super.onSelectedCamera(cameraSelected);
                        AppConfig.setCameraType(cameraSelected);
                        updateItemsAfterToggle();
                    }
                };
            }
            items.add(SettingsHelper.asCustomWithBackground(cameraTypeSelectorRow, cameraTypeSelector));
            items.add(UItem.asShadow(-2, getCameraAdvise()));
        }

        items.add(UItem.asHeader(-3, getString(R.string.NM_Category_Camera)));
        items.add(SettingsHelper.asSwitchCG(cameraControlButtonsRow, getString(R.string.NM_CenterCameraControlButtons), getString(R.string.NM_CenterCameraControlButtons_Desc))
                .setChecked(AppConfig.centerCameraControlButtons)
        );
        if (!AppConfig.centerCameraControlButtons) {
            items.add(UItem.asButton(cameraControlButtonsSideRow, getString(R.string.NM_CAM_ControlsSide),
                    getString(AppConfig.cameraControlButtonsRight
                            ? R.string.NM_ZoomSliderPosition_Right : R.string.NM_ZoomSliderPosition_Left)));
        }
        if (BuildVars.DEBUG_VERSION) {
            items.add(SettingsHelper.asSwitchCG(disableAttachCameraRow, getString(R.string.NM_DisableCam), getString(R.string.NM_DisableCam_Desc))
                    .setChecked(AppConfig.disableAttachCamera)
            );
        }
        items.add(UItem.asShadow(-4, null));

        items.add(UItem.asHeader(-5, getString(R.string.NM_Header_Videomessages)));
        if (upstreamRoundCamera2) {

            items.add(UItem.asButton(roundVideoSettingsRow, getString(R.string.RoundVideoSettings), "Camera2"));
        } else {
            items.add(SettingsActivity.SettingCell.Factory.of(rearCamRow, 0, 0, 0,
                    getString(R.string.NM_CAM_RoundCamera), getString(R.string.NM_CAM_RoundCameraDesc),
                    getRoundCameraText()));
        }
        if (advanced && !upstreamRoundCamera2) {
            items.add(SettingsHelper.asSwitchCG(cameraUseDualCameraRow, getString(R.string.NM_CameraDualCamera), getString(R.string.NM_CameraDualCamera_Desc))
                    .setChecked(AppConfig.useDualCamera)
            );
        }
        if (cameraX) {
            if (!upstreamRoundCamera2) {
                items.add(SettingsHelper.asSwitchCG(roundZoomScaleRow,
                        getString(R.string.NM_CAM_RoundZoomScale), getString(R.string.NM_CAM_RoundZoomScaleDesc))
                        .setChecked(AppConfig.roundZoomScale));
            }
            items.add(SettingsHelper.asSwitchCG(smoothCameraModuleTransitionsRow,
                    getString(R.string.NM_CAM_SmoothModules), getString(R.string.NM_CAM_SmoothModulesDesc))
                    .setChecked(AppConfig.smoothCameraModuleTransitions));
            items.add(SettingsHelper.asSwitchCG(roundVideoTapFocusRow,
                    getString(R.string.NM_CAM_RoundVideoTapFocus), getString(R.string.NM_CAM_RoundVideoTapFocusDesc))
                    .setChecked(AppConfig.roundVideoTapFocus));
            items.add(SettingsHelper.asSwitchCG(startFromUltraWideRow, getString(R.string.NM_CameraUW), getString(R.string.NM_CameraUW_Desc))
                    .setChecked(AppConfig.startFromUltraWideCam)
            );
        }
        items.add(UItem.asShadow(-6, null));

        if (!upstreamRoundCamera2) {
            items.add(UItem.asHeader(-9, getString(R.string.NM_CAM_VideoQuality)));
            items.add(UItem.asButton(roundVideoSizeRow, getString(R.string.NM_CAM_RoundVideoSize), getRoundVideoSizeText()));
            items.add(UItem.asButton(roundVideoBitrateRow, getString(R.string.NM_CAM_RoundVideoBitrate), getRoundVideoBitrateText()));
        } else if (advanced) {

            items.add(UItem.asHeader(-10, getString(R.string.NM_Category_Camera)));
        }

        if (advanced) {
            items.add(UItem.asButton(cameraXQualityRow, getString(R.string.NM_CameraQuality),
                    getCameraQualityText(AppConfig.cameraResolution)));
            items.add(UItem.asButton(cameraXFpsRangeRow, getString(R.string.NM_CAM_FpsRange), getCameraXFpsRange()));
            items.add(SettingsHelper.asSwitchCG(cameraStabilisationRow, getString(R.string.NM_CameraStabilisation))
                    .setChecked(AppConfig.cameraStabilisation)
            );
        }

        if (advanced) {
            items.add(UItem.asShadowCollapseButton(cameraImprovementsRow, getString(R.string.NM_CAM_Improvements) + "  ")
                    .setCollapsed(!cameraImprovementsExpanded));
            if (cameraImprovementsExpanded) {
                items.add(SettingsHelper.asSwitchCG(opticalStabilizationRow, getString(R.string.NM_CAM_OpticalStabilization), getString(R.string.NM_CAM_OpticalStabilization_Desc))
                        .setChecked(AppConfig.cameraOpticalStabilization)
                );
                items.add(SettingsHelper.asSwitchCG(continuousFocusRow, getString(R.string.NM_CAM_ContinuousFocus), getString(R.string.NM_CAM_ContinuousFocus_Desc))
                        .setChecked(AppConfig.cameraContinuousFocus)
                );
                items.add(SettingsHelper.asSwitchCG(noiseReductionRow, getString(R.string.NM_Camera_NoiseReduction), getString(R.string.NM_Camera_NoiseReduction_Desc))
                        .setChecked(AppConfig.cameraNoiseReduction)
                );
                items.add(SettingsHelper.asSwitchCG(faceDetectionRow, getString(R.string.NM_Camera_FaceDetection), getString(R.string.NM_Camera_FaceDetection_Desc))
                        .setChecked(AppConfig.cameraFaceDetection)
                );
                items.add(SettingsHelper.asSwitchCG(useHighRangeRow, getString(R.string.NM_Camera_UseHighRange), getString(R.string.NM_Camera_UseHighRange_Desc))
                        .setChecked(AppConfig.cameraXUseHighRange)
                );
            }
        }
        items.add(UItem.asShadow(-11, null));
    }

    @Override
    public CameraPreferencesActivity openAtSetting(int itemId) {
        if (itemId >= opticalStabilizationRow && itemId <= useHighRangeRow) {
            cameraImprovementsExpanded = true;
        }
        super.openAtSetting(itemId);
        return this;
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == roundVideoSettingsRow) {
            presentFragment(new RoundVideoSettingsActivity());
        } else if (item.id == disableAttachCameraRow) {
            AppConfig.toggleDisableAttachCamera();
            updateCheckState(view, AppConfig.disableAttachCamera);

            showRestartBulletin();
        } else if (item.id == cameraUseDualCameraRow) {
            AppConfig.toggleUseDualCamera();
            item.checked = AppConfig.useDualCamera;
            updateCheckState(view, AppConfig.useDualCamera);

            if (CameraXUtils.isCurrentCameraNotCameraX()) updateItemsAfterToggle();
        } else if (item.id == rearCamRow) {
            ArrayList<CharSequence> opts = new ArrayList<>();
            opts.add(getString(R.string.NM_CAM_FrontCamera));
            opts.add(getString(R.string.NM_CAM_RearCamera));
            opts.add(getString(R.string.NM_CAM_AskCamera));
            PopupHelper.show(opts, getString(R.string.NM_CAM_RoundCamera), AppConfig.videoMessagesCamera, getContext(), i -> {
                AppConfig.setVideoMessagesCamera(i);
                SettingsHelper.updateButtonValue(view, getRoundCameraText());
            });
        } else if (item.id == roundZoomScaleRow) {
            AppConfig.toggleRoundZoomScale();
            updateCheckState(view, AppConfig.roundZoomScale);
        } else if (item.id == smoothCameraModuleTransitionsRow) {
            AppConfig.toggleSmoothCameraModuleTransitions();
            updateCheckState(view, AppConfig.smoothCameraModuleTransitions);
        } else if (item.id == roundVideoTapFocusRow) {
            AppConfig.toggleRoundVideoTapFocus();
            updateCheckState(view, AppConfig.roundVideoTapFocus);
        } else if (item.id == startFromUltraWideRow) {
            AppConfig.toggleStartFromUltraWideCam();
            updateCheckState(view, AppConfig.startFromUltraWideCam);
        } else if (item.id == cameraStabilisationRow) {
            AppConfig.toggleCameraStabilisation();
            updateCheckState(view, AppConfig.cameraStabilisation);
        } else if (item.id == cameraXFpsRangeRow) {
            ArrayList<String> configStringKeys = new ArrayList<>();
            ArrayList<Integer> configValues = new ArrayList<>();

            configStringKeys.add("25-30");
            configValues.add(AppConfig.CameraXFpsRange25to30);

            configStringKeys.add("30-30");
            configValues.add(AppConfig.CameraXFpsRange30to30);

            if (isExtendedFpsAvailable()) {
                configStringKeys.add("30-60");
                configValues.add(AppConfig.CameraXFpsRange30to60);
            }

            configStringKeys.add(getString(R.string.Default));
            configValues.add(AppConfig.CameraXFpsRangeDefault);

            PopupHelper.showLegacy(configStringKeys, "FPS", configValues.indexOf(AppConfig.cameraXFpsRange), getContext(), i -> {
                AppConfig.setCameraXFpsRange(configValues.get(i));
                SettingsHelper.updateButtonValue(view, getCameraXFpsRange());
            });
        } else if (item.id == cameraXQualityRow) {
            ArrayList<Integer> types = getAvailableCameraQualityHeights();
            ArrayList<Integer> finalTypes = types;
            ArrayList<String> labels = finalTypes.stream().map(this::getCameraQualityText)
                    .collect(Collectors.toCollection(ArrayList::new));
            PopupHelper.showLegacy(labels, getString(R.string.NM_CameraQuality),
                    finalTypes.indexOf(AppConfig.cameraResolution), getContext(), i -> {
                AppConfig.setCameraResolution(finalTypes.get(i));
                SettingsHelper.updateButtonValue(view,
                        getCameraQualityText(AppConfig.cameraResolution));
            });
        } else if (item.id == roundVideoSizeRow) {

            ArrayList<CharSequence> labels = new ArrayList<>();
            ArrayList<Integer> values = new ArrayList<>();
            labels.add(getString(R.string.NM_CAM_RoundVideoSize_Auto)); values.add(AppConfig.ROUND_AUTO);
            labels.add(getString(R.string.NM_CAM_RoundVideoSize_SD));   values.add(AppConfig.ROUND_SD);
            labels.add(getString(R.string.NM_CAM_RoundVideoSize_STD));  values.add(AppConfig.ROUND_STD);
            labels.add(getString(R.string.NM_CAM_RoundVideoSize_HD));   values.add(AppConfig.ROUND_HD);
            if (AppConfig.videoMessagesResolution == AppConfig.ROUND_FHD) {
                labels.add("720 × 720"); values.add(AppConfig.ROUND_FHD);
            }
            int cur = values.indexOf(AppConfig.videoMessagesResolution);
            if (cur < 0) cur = values.indexOf(AppConfig.ROUND_HD);
            PopupHelper.showLegacy(labels, getString(R.string.NM_CAM_RoundVideoSize), cur, getContext(), i -> {
                AppConfig.setVideoMessagesResolution(values.get(i));
                SettingsHelper.updateButtonValue(view, getRoundVideoSizeText());
            });
        } else if (item.id == roundVideoBitrateRow) {
            ArrayList<CharSequence> labels = new ArrayList<>();
            ArrayList<Integer> values = new ArrayList<>();
            labels.add("1000 kbps");  values.add(1000);
            labels.add("1500 kbps");  values.add(1500);
            labels.add("2200 kbps");  values.add(2200);
            labels.add("3000 kbps");  values.add(3000);
            labels.add("4000 kbps");  values.add(4000);
            int cur = values.indexOf(AppConfig.videoMessagesBitrateKbps);
            if (cur < 0) cur = values.indexOf(1500);
            PopupHelper.showLegacy(labels, getString(R.string.NM_CAM_RoundVideoBitrate), cur, getContext(), i -> {
                AppConfig.setVideoMessagesBitrateKbps(values.get(i));
                SettingsHelper.updateButtonValue(view, getRoundVideoBitrateText());
            });
        } else if (item.id == cameraControlButtonsRow) {
            AppConfig.toggleCenterCameraControlButtons();
            updateCheckState(view, AppConfig.centerCameraControlButtons);
            updateItemsAfterToggle();
        } else if (item.id == cameraControlButtonsSideRow) {
            ArrayList<CharSequence> positions = new ArrayList<>();
            positions.add(getString(R.string.NM_ZoomSliderPosition_Left));
            positions.add(getString(R.string.NM_ZoomSliderPosition_Right));
            PopupHelper.show(positions, getString(R.string.NM_CAM_ControlsSide),
                    AppConfig.cameraControlButtonsRight ? 1 : 0, getContext(), i -> {
                        AppConfig.setCameraControlButtonsRight(i == 1);
                        SettingsHelper.updateButtonValue(view, positions.get(i).toString());
                    });
        } else if (item.id == cameraImprovementsRow) {
            cameraImprovementsExpanded = !cameraImprovementsExpanded;
            updateItemsAfterToggle();
        } else if (item.id == opticalStabilizationRow) {
            AppConfig.toggleCameraOpticalStabilization();
            updateCheckState(view, AppConfig.cameraOpticalStabilization);
        } else if (item.id == continuousFocusRow) {
            AppConfig.toggleCameraContinuousFocus();
            updateCheckState(view, AppConfig.cameraContinuousFocus);
        } else if (item.id == noiseReductionRow) {
            AppConfig.toggleCameraNoiseReduction();
            updateCheckState(view, AppConfig.cameraNoiseReduction);
        } else if (item.id == faceDetectionRow) {
            AppConfig.toggleCameraFaceDetection();
            updateCheckState(view, AppConfig.cameraFaceDetection);
        } else if (item.id == useHighRangeRow) {
            AppConfig.toggleCameraXUseHighRange();
            updateCheckState(view, AppConfig.cameraXUseHighRange);

            if (!AppConfig.cameraXUseHighRange
                    && AppConfig.cameraXFpsRange
                    == AppConfig.CameraXFpsRange30to60) {
                AppConfig.setCameraXFpsRange(AppConfig.CameraXFpsRange30to30);
                updateItemsAfterToggle();
            }
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private boolean isExtendedFpsAvailable() {

        return AppConfig.cameraXUseHighRange;
    }

    private ArrayList<Integer> getAvailableCameraQualityHeights() {
        ArrayList<Integer> result = new ArrayList<>(3);
        result.add(AppConfig.CAMERA_RESOLUTION_2K);
        result.add(AppConfig.CAMERA_RESOLUTION_1080P);
        result.add(AppConfig.CAMERA_RESOLUTION_720P);
        return result;
    }

    private String getCameraQualityText(int height) {
        if (height == AppConfig.CAMERA_RESOLUTION_2K) {
            return getString(R.string.Quality1440Short);
        }
        return height + "p";
    }

    public static String getCameraName() {
        return switch (AppConfig.cameraType) {
            case AppConfig.TELEGRAM_CAMERA -> "Telegram";
            case AppConfig.CAMERA_X -> "CameraX";
            case AppConfig.CAMERA_2 -> "Camera 2 (Telegram)";
            default -> getString(R.string.NM_CameraTypeSystem);
        };
    }

    private CharSequence getCameraAdvise() {
        String advise = switch (AppConfig.cameraType) {
            case AppConfig.TELEGRAM_CAMERA -> getString(R.string.NM_DefaultCameraDesc);
            case AppConfig.CAMERA_X -> getString(R.string.NM_CameraXDesc);
            case AppConfig.CAMERA_2 -> getString(R.string.NM_Camera2Desc);
            default -> getString(R.string.NM_SystemCameraDesc);
        };

        Spannable htmlParsed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            htmlParsed = new SpannableString(Html.fromHtml(advise, Html.FROM_HTML_MODE_LEGACY));
        } else {
            htmlParsed = new SpannableString(Html.fromHtml(advise));
        }

        return ResourcesUtils.getUrlNoUnderlineText(htmlParsed);
    }

    private String getCameraXFpsRange() {
        return switch (AppConfig.cameraXFpsRange) {
            case AppConfig.CameraXFpsRange25to30 -> "25-30";
            case AppConfig.CameraXFpsRange30to30 -> "30-30";
            case AppConfig.CameraXFpsRange30to60 -> "30-60";

            case AppConfig.CameraXFpsRange60to60 -> "30-60";
            default -> getString(R.string.Default);
        };
    }

    private String getRoundCameraText() {
        switch (AppConfig.videoMessagesCamera) {
            case 1: return getString(R.string.NM_CAM_RearCamera);
            case 2: return getString(R.string.NM_CAM_AskCamera);
            default: return getString(R.string.NM_CAM_FrontCamera);
        }
    }

    private String getRoundVideoSizeText() {
        return switch (AppConfig.videoMessagesResolution) {
            case AppConfig.ROUND_SD -> getString(R.string.NM_CAM_RoundVideoSize_SD);
            case AppConfig.ROUND_STD -> getString(R.string.NM_CAM_RoundVideoSize_STD);
            case AppConfig.ROUND_HD -> getString(R.string.NM_CAM_RoundVideoSize_HD);
            case AppConfig.ROUND_FHD -> "720 × 720";
            default -> getString(R.string.NM_CAM_RoundVideoSize_Auto);
        };
    }

    private String getRoundVideoBitrateText() {
        return AppConfig.videoMessagesBitrateKbps + " kbps";
    }
}
