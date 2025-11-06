import argparse
import sys
import time
import threading
from dataclasses import dataclass
from typing import List, Tuple, Optional

import numpy as np
import cv2

# Third-party runtime deps; guarded imports for friendlier errors when not installed yet
try:
    import pyautogui  # type: ignore
except Exception as exc:  # pragma: no cover
    pyautogui = None  # type: ignore
    _pyautogui_import_error = exc
else:
    _pyautogui_import_error = None

try:
    import keyboard  # type: ignore
except Exception as exc:  # pragma: no cover
    keyboard = None  # type: ignore
    _keyboard_import_error = exc
else:
    _keyboard_import_error = None

try:
    from skimage.morphology import skeletonize  # type: ignore
except Exception as exc:  # pragma: no cover
    skeletonize = None  # type: ignore
    _skeletonize_import_error = exc
else:
    _skeletonize_import_error = None


Point = Tuple[int, int]


@dataclass
class CanvasRect:
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def width(self) -> int:
        return max(0, self.x2 - self.x1)

    @property
    def height(self) -> int:
        return max(0, self.y2 - self.y1)


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Auto Sketch Drawer - draw an image as edge strokes on any canvas")
    parser.add_argument("--image", required=True, help="Path to input image")
    parser.add_argument("--speed", type=float, default=1.0, help="Drawing speed scaler (higher is faster)")
    parser.add_argument("--detail", choices=["low", "med", "high"], default="high", help="Edge detail level")
    parser.add_argument("--fit", choices=["contain", "fill"], default="contain", help="Fit mode from image to canvas")
    parser.add_argument("--invert", action="store_true", help="Invert image before edge detection")
    parser.add_argument("--dry-run", action="store_true", help="Do not move mouse; just compute and optionally preview")
    parser.add_argument("--preview", action="store_true", help="Show a preview window of detected paths before drawing")
    parser.add_argument("--max-stroke-len", type=int, default=800, help="Max points per continuous stroke segment")
    parser.add_argument("--pps", type=int, default=1200, help="Target points per second while dragging")
    parser.add_argument("--min-path-len", type=int, default=5, help="Minimum polyline length to keep (points)")
    parser.add_argument("--spacing-px", type=int, default=2, help="Max screen-pixel spacing between points after resampling (set ~25–40% of brush diameter)")
    parser.add_argument("--drag-duration", type=float, default=0.0, help="Tiny per-step duration for drag/move (e.g., 0.005–0.015) to force intermediate events on laggy canvases")
    parser.add_argument("--use-move", action="store_true", help="Use mouseDown + moveTo steps instead of dragTo for apps that handle move events better")
    return parser.parse_args(argv)


def load_image(path: str, invert: bool) -> np.ndarray:
    img = cv2.imread(path, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f"Could not read image: {path}")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if invert:
        gray = cv2.bitwise_not(gray)
    return gray


def multiscale_canny(gray: np.ndarray, detail: str) -> np.ndarray:
    if detail == "high":
        sigmas = [0.8, 1.2, 1.8, 2.6, 3.5]
        low_scale = 0.55
        high_scale = 1.45
    elif detail == "med":
        sigmas = [0.8, 1.6, 3.2]
        low_scale = 0.66
        high_scale = 1.33
    else:
        sigmas = [1.0, 2.0]
        low_scale = 0.75
        high_scale = 1.25

    edges = np.zeros_like(gray, dtype=np.uint8)
    for sigma in sigmas:
        blur = cv2.GaussianBlur(gray, (0, 0), sigmaX=sigma, sigmaY=sigma)
        v = float(np.median(blur))
        low = int(max(0, low_scale * v))
        high = int(min(255, high_scale * v))
        e = cv2.Canny(blur, low, high, L2gradient=True)
        edges = cv2.bitwise_or(edges, e)
    return edges


def thin_edges(edge_img: np.ndarray) -> np.ndarray:
    if skeletonize is None:
        # Fallback: simple morphology to clean; not true thinning but acceptable if scikit-image missing
        kernel = np.ones((3, 3), np.uint8)
        cleaned = cv2.morphologyEx(edge_img, cv2.MORPH_OPEN, kernel, iterations=1)
        return cleaned
    # skeletonize expects boolean image
    bool_img = edge_img > 0
    skel = skeletonize(bool_img)
    return (skel.astype(np.uint8)) * 255


def extract_polylines(edge_img: np.ndarray, min_len: int) -> List[np.ndarray]:
    contours, _ = cv2.findContours(edge_img, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    polylines: List[np.ndarray] = []
    for cnt in contours:
        if cnt is None or len(cnt) < min_len:
            continue
        pts = cnt.squeeze(1)
        if pts.ndim != 2 or pts.shape[0] < min_len:
            continue
        polylines.append(pts.astype(np.int32))
    return polylines


def order_paths(polylines: List[np.ndarray]) -> List[np.ndarray]:
    # Simple ordering by tile scan (y-major then x) to reduce long hops
    def keyfn(arr: np.ndarray) -> Tuple[int, int]:
        yx = arr[0]
        return (int(yx[1]), int(yx[0]))

    return sorted(polylines, key=keyfn)


def split_long_paths(path: np.ndarray, max_points: int) -> List[np.ndarray]:
    if len(path) <= max_points:
        return [path]
    chunks: List[np.ndarray] = []
    for i in range(0, len(path), max_points):
        chunk = path[i:i + max_points]
        if len(chunk) >= 2:
            chunks.append(chunk)
    return chunks


def compute_fit_transform(src_w: int, src_h: int, rect: CanvasRect, fit: str) -> Tuple[float, float, float, float]:
    # Returns (scale_x, scale_y, offset_x, offset_y) for mapping (ix,iy) -> (sx,sy)
    if fit == "fill":
        scale_x = rect.width / max(1.0, float(src_w))
        scale_y = rect.height / max(1.0, float(src_h))
        off_x = rect.x1
        off_y = rect.y1
        return scale_x, scale_y, off_x, off_y
    # contain
    scale = min(rect.width / max(1.0, float(src_w)), rect.height / max(1.0, float(src_h)))
    scaled_w = scale * src_w
    scaled_h = scale * src_h
    off_x = rect.x1 + (rect.width - scaled_w) / 2.0
    off_y = rect.y1 + (rect.height - scaled_h) / 2.0
    return scale, scale, off_x, off_y


def map_point(ix: int, iy: int, scale_x: float, scale_y: float, off_x: float, off_y: float) -> Tuple[int, int]:
    sx = int(round(off_x + ix * scale_x))
    sy = int(round(off_y + iy * scale_y))
    return sx, sy


def resample_polyline_screen(points: List[Point], max_step: int) -> List[Point]:
    """Resample a screen-space polyline so successive points are at most max_step apart.

    Uses linear interpolation between each pair of points and inserts intermediate
    integer coordinate points as needed. Ensures the original endpoints are kept.
    """
    if not points:
        return []
    step = max(1, int(max_step))
    resampled: List[Point] = [points[0]]
    prev_x, prev_y = points[0]
    for idx in range(1, len(points)):
        curr_x, curr_y = points[idx]
        dx = curr_x - prev_x
        dy = curr_y - prev_y
        dist = int(max(abs(dx), abs(dy))) if (dx == 0 or dy == 0) else int(np.hypot(dx, dy))
        if dist <= step:
            # Close enough; just append current point, but avoid duplicates
            if resampled[-1] != (curr_x, curr_y):
                resampled.append((curr_x, curr_y))
            prev_x, prev_y = curr_x, curr_y
            continue
        # Number of intermediate samples (excluding start, including end)
        num_segments = max(1, int(np.ceil(dist / float(step))))
        # Generate intermediate points linearly
        for s in range(1, num_segments + 1):
            t = s / float(num_segments)
            ix = int(round(prev_x + t * dx))
            iy = int(round(prev_y + t * dy))
            if resampled[-1] != (ix, iy):
                resampled.append((ix, iy))
        prev_x, prev_y = curr_x, curr_y
    # Ensure at least two points for drawing logic downstream
    if len(resampled) == 1 and resampled[0] != points[-1]:
        resampled.append(points[-1])
    return resampled


class Calibrator:
    def __init__(self) -> None:
        self.tl: Optional[Point] = None
        self.br: Optional[Point] = None
        self.confirmed: bool = False
        self._lock = threading.Lock()

    def _record_tl(self) -> None:
        if pyautogui is None:
            return
        pos = pyautogui.position()
        with self._lock:
            self.tl = (int(pos.x), int(pos.y))

    def _record_br(self) -> None:
        if pyautogui is None:
            return
        pos = pyautogui.position()
        with self._lock:
            self.br = (int(pos.x), int(pos.y))

    def _confirm(self) -> None:
        with self._lock:
            self.confirmed = True

    def run(self) -> CanvasRect:
        if keyboard is None:
            raise RuntimeError("keyboard module not available. Install and run with sufficient privileges.")
        print("Bring your drawing app to front, select your brush and color.")
        print("Hover mouse over TOP-LEFT of the canvas and press F8.")
        print("Hover mouse over BOTTOM-RIGHT of the canvas and press F9.")
        print("Press F10 to confirm. Press ESC to cancel.")

        keyboard.add_hotkey("f8", self._record_tl)
        keyboard.add_hotkey("f9", self._record_br)
        keyboard.add_hotkey("f10", self._confirm)

        try:
            while True:
                if keyboard.is_pressed("esc"):
                    raise KeyboardInterrupt
                with self._lock:
                    tl = self.tl
                    br = self.br
                    confirmed = self.confirmed
                status = f"TL={tl} BR={br}"
                print(f"[Calibrate] {status}    ", end="\r", flush=True)
                if confirmed and tl and br:
                    x1, y1 = tl
                    x2, y2 = br
                    # normalize order
                    x1, x2 = (x1, x2) if x1 <= x2 else (x2, x1)
                    y1, y2 = (y1, y2) if y1 <= y2 else (y2, y1)
                    print()
                    return CanvasRect(x1, y1, x2, y2)
                time.sleep(0.05)
        finally:
            try:
                keyboard.remove_hotkey("f8")
                keyboard.remove_hotkey("f9")
                keyboard.remove_hotkey("f10")
            except Exception:
                pass


class DrawController:
    def __init__(self) -> None:
        self.paused = False
        self.aborted = False

    def install_hotkeys(self) -> None:
        if keyboard is None:
            print("Warning: keyboard module not available; pause/abort hotkeys disabled.")
            return
        keyboard.add_hotkey("f6", self._toggle_pause)
        keyboard.add_hotkey("f7", self._abort)

    def uninstall_hotkeys(self) -> None:
        if keyboard is None:
            return
        try:
            keyboard.remove_hotkey("f6")
            keyboard.remove_hotkey("f7")
        except Exception:
            pass

    def _toggle_pause(self) -> None:
        self.paused = not self.paused
        print(f"[Control] Paused={self.paused}")

    def _abort(self) -> None:
        self.aborted = True
        print("[Control] Abort requested")


def preview_paths(gray: np.ndarray, polylines: List[np.ndarray], window: str = "Preview") -> None:
    h, w = gray.shape[:2]
    vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    overlay = vis.copy()
    for pts in polylines:
        color = (0, 255, 0)
        for i in range(1, len(pts)):
            cv2.line(overlay, (int(pts[i-1][0]), int(pts[i-1][1])), (int(pts[i][0]), int(pts[i][1])), color, 1, cv2.LINE_AA)
    vis = cv2.addWeighted(overlay, 0.8, vis, 0.2, 0)
    cv2.imshow(window, vis)
    print("Close the preview window or press any key within it to continue...")
    cv2.waitKey(0)
    cv2.destroyWindow(window)


def draw_on_canvas(polylines: List[np.ndarray], img_shape: Tuple[int, int], rect: CanvasRect, fit_mode: str,
                   pps: int, speed: float, max_stroke_len: int, controller: DrawController, dry_run: bool,
                   spacing_px: int, drag_duration: float, use_move: bool) -> None:
    if pyautogui is None and not dry_run:
        raise RuntimeError(f"pyautogui not available: {_pyautogui_import_error}")

    ih, iw = img_shape
    sx, sy, ox, oy = compute_fit_transform(iw, ih, rect, fit_mode)

    # 3-second safety countdown
    if not dry_run:
        print("Starting in 3...")
        time.sleep(1)
        print("2...")
        time.sleep(1)
        print("1...")
        time.sleep(1)

    # Install control hotkeys
    controller.install_hotkeys()
    try:
        # Configure PyAutoGUI for speed and reliability
        if not dry_run:
            try:
                pyautogui.FAILSAFE = True
                pyautogui.PAUSE = 0
                # Not all versions expose these, so guard
                if hasattr(pyautogui, "MINIMUM_DURATION"):
                    pyautogui.MINIMUM_DURATION = 0
                if hasattr(pyautogui, "MINIMUM_SLEEP"):
                    pyautogui.MINIMUM_SLEEP = 0
            except Exception:
                pass

        points_per_second = max(60, int(pps * max(0.2, float(speed))))
        min_delay = 1.0 / points_per_second
        spacing_px = max(1, int(spacing_px))
        drag_duration = float(drag_duration)

        first_focus_done = False
        for path in polylines:
            if controller.aborted:
                break

            for chunk in split_long_paths(path, max_stroke_len):
                if controller.aborted:
                    break

                # Map all points to screen to avoid repeated math inside loop
                mapped = [map_point(int(p[0]), int(p[1]), sx, sy, ox, oy) for p in chunk]
                # Resample in screen space to ensure tight spacing regardless of lag
                mapped = resample_polyline_screen(mapped, spacing_px)
                if len(mapped) < 2:
                    continue

                if dry_run:
                    # Simulate time without moving
                    t0 = time.time()
                    for _ in mapped:
                        while controller.paused and not controller.aborted:
                            time.sleep(0.05)
                        if controller.aborted:
                            break
                        # pretend work
                        time.sleep(min_delay)
                    continue

                # Focus the canvas on the first stroke to ensure the app is ready
                if not first_focus_done:
                    pyautogui.click(mapped[0][0], mapped[0][1])
                    time.sleep(0.03)
                    first_focus_done = True

                # Use dragTo or moveTo (+ mouseDown) depending on preference
                pyautogui.moveTo(mapped[0][0], mapped[0][1], duration=0)
                pyautogui.mouseDown(button='left')
                time.sleep(0.01)
                for (mx, my) in mapped[1:]:
                    while controller.paused and not controller.aborted:
                        time.sleep(0.05)
                    if controller.aborted:
                        break
                    if use_move:
                        pyautogui.moveTo(mx, my, duration=max(0.0, drag_duration))
                    else:
                        pyautogui.dragTo(mx, my, duration=max(0.0, drag_duration), button='left')
                    # If we already spend time in OS via a non-zero duration, skip extra sleep
                    if drag_duration <= 0.0 and min_delay > 0:
                        time.sleep(min_delay)
                pyautogui.mouseUp(button='left')

                # Small breather between chunks
                time.sleep(0.01)
    finally:
        controller.uninstall_hotkeys()


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)

    # Validate optional modules early with helpful messages
    if args.preview or not args.dry_run:
        if _pyautogui_import_error and not args.dry_run:
            print("pyautogui is required for drawing:", _pyautogui_import_error, file=sys.stderr)
            return 2
        if _keyboard_import_error:
            print("keyboard is required for calibration/hotkeys:", _keyboard_import_error, file=sys.stderr)
            print("Tip: On Windows, run as Administrator if hotkeys do not register.")

    gray = load_image(args.image, args.invert)

    # Edge detection and thinning
    edges = multiscale_canny(gray, args.detail)
    thin = thin_edges(edges)

    # Extract and order paths
    polylines = extract_polylines(thin, min_len=args.min_path_len)
    polylines = order_paths(polylines)

    if args.preview:
        preview_paths(gray, polylines, window="Preview")

    # Calibration
    calibrator = Calibrator()
    rect = calibrator.run()
    if rect.width < 5 or rect.height < 5:
        print("Canvas rect too small.", file=sys.stderr)
        return 3

    controller = DrawController()
    draw_on_canvas(
        polylines=polylines,
        img_shape=gray.shape[:2],
        rect=rect,
        fit_mode=args.fit,
        pps=args.pps,
        speed=args.speed,
        max_stroke_len=args.max_stroke_len,
        controller=controller,
        dry_run=args.dry_run,
        spacing_px=args.spacing_px,
        drag_duration=args.drag_duration,
        use_move=args.use_move,
    )

    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


