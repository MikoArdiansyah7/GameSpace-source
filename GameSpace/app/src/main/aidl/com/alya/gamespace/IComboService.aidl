package com.alya.gamespace;

interface IComboService {
    void destroy() = 16777114;
    String info() = 1;
    boolean tap(in int[] xs, in int[] ys, int downMs) = 2;
}
