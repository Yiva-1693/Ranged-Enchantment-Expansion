package com.yiva.homingarrows.projectile;

/** 读取箭矢实际的嵌地状态；AbstractArrow 不通过 onGround() 表示插入方块。 */
public interface AbstractArrowAccess {

    boolean homingarrows$isInGround();
}
