#import "messaging.h"

@implementation PrimitiveTestSubject

+ (int)intFn {
    return 42;
}

+ (float)floatFn {
    return 3.14f;
}

+ (double)doubleFn {
    return 3.14;
}

@end;

@implementation AggregateTestSubject

+ (SingleFloat)singleFloatFn {
    SingleFloat s;
    s.f = 3.14f;
    return s;
}

+ (SimplePacked)simplePackedFn {
    SimplePacked s;
    s.f1 = '0';
    s.f2 = 111;
    return s;
}

+ (HomogeneousSmall)homogeneousSmallFn {
    HomogeneousSmall s;
    s.f1 = 1.0f;
    s.f2 = 2.0f;
    s.f3 = 3.0f;
    s.f4 = 4.0f;
    return s;
}

+ (HomogeneousBig)homogeneousBigFn {
    HomogeneousBig s;
    s.f1 = 1.0f;
    s.f2 = 2.0f;
    s.f3 = 3.0f;
    s.f4 = 4.0f;
    s.f5 = 5.0f;
    s.f6 = 6.0f;
    s.f7 = 7.0f;
    s.f8 = 8.0f;
    return s;
}

@end;