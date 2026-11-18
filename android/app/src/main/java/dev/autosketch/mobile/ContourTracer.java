package dev.autosketch.mobile;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Converts a single-pixel skeleton into ordered polylines. Paths are walked
 * through 8-connected neighbours, starting at endpoints and junctions, and
 * preferring the straightest continuation at every step. Loops are picked up
 * afterwards as closed chains.
 */
public final class ContourTracer {

    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    private ContourTracer() {
    }

    /** Returns chains of row-major pixel indices, each at least {@code minPoints} long. */
    public static ArrayList<int[]> trace(byte[] skeleton, int width, int height, int minPoints) {
        ArrayList<int[]> chains = new ArrayList<>();
        int size = width * height;
        if (size == 0) {
            return chains;
        }
        int[] degree = new int[size];
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                if (skeleton[i] == 0) {
                    continue;
                }
                int count = 0;
                for (int d = 0; d < 8; d++) {
                    if (neighbor(i, d, width, height, skeleton) >= 0) {
                        count++;
                    }
                }
                degree[i] = count;
            }
        }

        int[] visited = new int[size];
        for (int i = 0; i < size; i++) {
            int d = degree[i];
            if (d == 0 || d == 2) {
                continue;
            }
            for (int dir = 0; dir < 8; dir++) {
                if ((visited[i] & (1 << dir)) != 0) {
                    continue;
                }
                if (neighbor(i, dir, width, height, skeleton) < 0) {
                    continue;
                }
                int[] chain = walk(i, dir, skeleton, degree, visited, width, height);
                if (chain.length >= minPoints) {
                    chains.add(chain);
                }
            }
        }

        // Leftover edges (closed loops or pixels whose junction entry was used).
        for (int i = 0; i < size; i++) {
            if (degree[i] == 0) {
                continue;
            }
            for (int dir = 0; dir < 8; dir++) {
                if ((visited[i] & (1 << dir)) != 0) {
                    continue;
                }
                if (neighbor(i, dir, width, height, skeleton) < 0) {
                    continue;
                }
                int[] chain = walk(i, dir, skeleton, degree, visited, width, height);
                if (chain.length >= minPoints) {
                    chains.add(chain);
                }
            }
        }
        return chains;
    }

    /**
     * Connectivity used for both degrees and walking. Diagonal links are only
     * used when they are the sole connection: if an orthogonal neighbour also
     * joins the two pixels the diagonal is a redundant "corner cut". Ignoring
     * those keeps single-pixel curves at degree two, which is what makes long
     * continuous contours possible instead of hundreds of two-point fragments.
     */
    private static int neighbor(int index, int dir, int width, int height, byte[] mask) {
        int x = index % width + DX[dir];
        int y = index / width + DY[dir];
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return -1;
        }
        int next = y * width + x;
        if (mask[next] == 0) {
            return -1;
        }
        if ((dir & 1) != 0) {
            int horizontal = index + DX[dir];
            int vertical = index + DY[dir] * width;
            if (mask[horizontal] != 0 || mask[vertical] != 0) {
                return -1;
            }
        }
        return next;
    }

    private static int[] walk(int start, int firstDir, byte[] mask, int[] degree,
                              int[] visited, int width, int height) {
        int[] chain = new int[128];
        int count = 0;
        chain[count++] = start;
        int current = start;
        int dir = firstDir;
        int guard = width * height;
        while (guard-- > 0) {
            int next = neighbor(current, dir, width, height, mask);
            if (next < 0 || (visited[current] & (1 << dir)) != 0) {
                break;
            }
            visited[current] |= 1 << dir;
            visited[next] |= 1 << ((dir + 4) & 7);
            if (count == chain.length) {
                chain = Arrays.copyOf(chain, chain.length * 2);
            }
            chain[count++] = next;
            if (degree[next] != 2) {
                break;
            }
            int nextDir = -1;
            int bestTurn = Integer.MAX_VALUE;
            for (int d = 0; d < 8; d++) {
                if ((visited[next] & (1 << d)) != 0) {
                    continue;
                }
                if (neighbor(next, d, width, height, mask) < 0) {
                    continue;
                }
                int turn = Math.abs(d - dir);
                if (turn > 4) {
                    turn = 8 - turn;
                }
                if (turn < bestTurn) {
                    bestTurn = turn;
                    nextDir = d;
                }
            }
            if (nextDir < 0) {
                break;
            }
            current = next;
            dir = nextDir;
        }
        return Arrays.copyOf(chain, count);
    }
}
