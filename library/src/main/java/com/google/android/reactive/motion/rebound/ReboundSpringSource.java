/*
 * Copyright 2016-present The Material Motion Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.reactive.motion.rebound;

import android.support.v4.util.SimpleArrayMap;

import com.facebook.rebound.OrigamiValueConverter;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.google.android.indefinite.observable.IndefiniteObservable.Subscription;
import com.google.android.indefinite.observable.Observer;
import com.google.android.reactive.motion.MotionObservable;
import com.google.android.reactive.motion.MotionObservable.MotionObserver;
import com.google.android.reactive.motion.MotionObservable.SimpleMotionObserver;
import com.google.android.reactive.motion.interactions.MaterialSpring;
import com.google.android.reactive.motion.rebound.CompositeReboundSpring.CompositeSpringListener;
import com.google.android.reactive.motion.sources.SpringSource;

/**
 * A source for rebound springs.
 * <p>
 * Rebound springs only support animating between float values. This class supports arbitrary T
 * values by vectorizing the value into floats, and animating them individually using separate
 * rebound springs.
 */
public final class ReboundSpringSource<T> extends SpringSource<T> {

  public static final System SYSTEM = new System() {
    @Override
    public <T> SpringSource<T> create(MaterialSpring<?, T> spring) {
      return new ReboundSpringSource<>(spring);
    }
  };

  private final SpringSystem springSystem = SpringSystem.create();
  private final MaterialSpring<?, T> spring;

  private final Spring[] reboundSprings;
  private final CompositeReboundSpring compositeSpring;
  private final SimpleArrayMap<Observer<T>, CompositeSpringListener> springListeners = new SimpleArrayMap<>();

  private Subscription destinationSubscription;
  private Subscription frictionSubscription;
  private Subscription tensionSubscription;

  public ReboundSpringSource(MaterialSpring<?, T> spring) {
    super(spring);
    this.spring = spring;
    reboundSprings = new Spring[spring.vectorizer.getVectorLength()];
    for (int i = 0; i < reboundSprings.length; i++) {
      reboundSprings[i] = springSystem.createSpring();
    }

    compositeSpring = new CompositeReboundSpring(reboundSprings);
    compositeSpring.addListener(new CompositeSpringListener() {
      @Override
      public void onCompositeSpringActivate() {
        for (int i = 0, count = springListeners.size(); i < count; i++) {
          springListeners.valueAt(i).onCompositeSpringActivate();
        }
      }

      @Override
      public void onCompositeSpringUpdate(float[] values) {
        for (int i = 0, count = springListeners.size(); i < count; i++) {
          springListeners.valueAt(i).onCompositeSpringUpdate(values);
        }
      }

      @Override
      public void onCompositeSpringAtRest() {
        for (int i = 0, count = springListeners.size(); i < count; i++) {
          springListeners.valueAt(i).onCompositeSpringAtRest();
        }
      }
    });
  }

  @Override
  protected void onConnect(final MotionObserver<T> observer) {
    springListeners.put(observer, new CompositeSpringListener() {

      @Override
      public void onCompositeSpringActivate() {
        observer.state(MotionObservable.ACTIVE);
      }

      @Override
      public void onCompositeSpringUpdate(float[] values) {
        T value = spring.vectorizer.compose(values);
        observer.next(value);
      }

      @Override
      public void onCompositeSpringAtRest() {
        observer.state(MotionObservable.AT_REST);
      }
    });
  }

  @Override
  protected void onEnable(MotionObserver<T> observer) {
    final SpringConfig springConfig = new SpringConfig(0, 0);
    tensionSubscription = spring.tension.subscribe(new SimpleMotionObserver<Float>() {
      @Override
      public void next(Float value) {
        springConfig.tension = OrigamiValueConverter.tensionFromOrigamiValue(value);
      }
    });
    frictionSubscription = spring.friction.subscribe(new SimpleMotionObserver<Float>() {
      @Override
      public void next(Float value) {
        springConfig.friction = OrigamiValueConverter.frictionFromOrigamiValue(value);
      }
    });

    final int count = reboundSprings.length;

    float[] initialValues = new float[count];
    spring.vectorizer.vectorize(spring.initialValue.read(), initialValues);

    float[] initialVelocities = new float[count];
    spring.vectorizer.vectorize(spring.initialVelocity.read(), initialVelocities);

    for (int i = 0; i < count; i++) {
      reboundSprings[i].setSpringConfig(springConfig);
      reboundSprings[i].setCurrentValue(initialValues[i]);
      reboundSprings[i].setVelocity(initialVelocities[i]);
    }

    final float[] endValues = new float[count];
    destinationSubscription = spring.destination.subscribe(new SimpleMotionObserver<T>() {
      @Override
      public void next(T value) {
        spring.vectorizer.vectorize(value, endValues);

        for (int i = 0; i < count; i++) {
          reboundSprings[i].setEndValue(endValues[i]);
        }
      }
    });
  }

  @Override
  protected void onDisable(MotionObserver<T> observer) {
    tensionSubscription.unsubscribe();
    frictionSubscription.unsubscribe();
    destinationSubscription.unsubscribe();

    for (int i = 0; i < reboundSprings.length; i++) {
      reboundSprings[i].setAtRest();
    }
  }

  @Override
  protected void onDisconnect(MotionObserver<T> observer) {
    springListeners.remove(observer);
  }
}
